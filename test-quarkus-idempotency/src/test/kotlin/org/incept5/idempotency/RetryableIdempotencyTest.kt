package org.incept5.idempotency

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers
import org.incept5.example.ExampleCommand
import org.incept5.example.WidgetService
import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.exp.IdempotencyConflictException
import org.incept5.idempotency.repository.IdempotencyRecordRepository
import org.incept5.idempotency.service.IdempotencyHasher
import org.incept5.idempotency.service.IdempotencyRecordCreator
import org.incept5.idempotency.service.IdempotencyTransactions
import org.incept5.idempotency.tasks.DeleteExpiredIdempotencyRecordsTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * FF-4394: retryable failures (IdempotencyOptions.retryOn) and the SUCCESSFUL status written in the
 * operation's transaction (IdempotencyOptions.successInOperationTransaction), against a real Postgres.
 */
@QuarkusTest
class RetryableIdempotencyTest {

    @Inject
    lateinit var repository: IdempotencyRecordRepository

    @Inject
    lateinit var recordCreator: IdempotencyRecordCreator

    @Inject
    lateinit var transactions: IdempotencyTransactions

    @Inject
    lateinit var expiryTask: DeleteExpiredIdempotencyRecordsTask

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var widgetService: WidgetService

    private val orgId = "org-${UUID.randomUUID()}"

    private fun uniqueName(prefix: String = "widget") = "$prefix-${UUID.randomUUID()}"

    private fun post(key: String, name: String, retry: Boolean = false, inTx: Boolean = false): Response =
        RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", key)
            .queryParam("retry", retry)
            .queryParam("inTx", inTx)
            .body(ExampleCommand(orgId, name))
            .`when`()
            .post("/widgets")

    private fun record(key: String): IdempotencyRecord? = repository.findRecordByKeyAndContext(key, orgId)

    private fun failNext(name: String, times: Int) {
        WidgetService.transientFailures[name] = AtomicInteger(times)
    }

    @Test
    fun `default options cache a temporary failure as ERROR and replay it (1_0_13 parity)`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName()
        failNext(name, 1)

        post(key, name).then().statusCode(500)
        assertEquals(IdempotencyStatus.ERROR, record(key)!!.status)

        post(key, name).then().statusCode(500)
        assertEquals(1, WidgetService.runsFor(name))
        assertEquals(0, widgetService.countWidgets(name))
    }

    @Test
    fun `unlessRejected - a temporary failure is RETRYABLE, the same request then succeeds and a third call replays`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName()
        failNext(name, 1)

        post(key, name, retry = true).then().statusCode(500)
        val afterFailure = record(key)!!
        assertEquals(IdempotencyStatus.RETRYABLE, afterFailure.status)
        assertEquals(IdempotencyHasher.hashRequest(ExampleCommand(orgId, name)), afterFailure.requestHash)
        assertNull(afterFailure.response)
        assertEquals(0, widgetService.countWidgets(name))

        val id = post(key, name, retry = true).then().statusCode(200).extract().path<String>("id")
        assertEquals(IdempotencyStatus.SUCCESSFUL, record(key)!!.status)
        assertEquals(1, widgetService.countWidgets(name))

        post(key, name, retry = true).then().statusCode(200).body("id", CoreMatchers.equalTo(id))
        assertEquals(2, WidgetService.runsFor(name))
    }

    @Test
    fun `unlessRejected - a validation rejection is cached and replayed without re-running the operation`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName("reject")

        post(key, name, retry = true).then().statusCode(400).body(CoreMatchers.containsString("widget_rejected"))
        assertEquals(IdempotencyStatus.ERROR, record(key)!!.status)

        post(key, name, retry = true).then().statusCode(400).body(CoreMatchers.containsString("widget_rejected"))
        assertEquals(1, WidgetService.runsFor(name))
    }

    @Test
    fun `in-transaction success - a failed status write rolls the work back and the key does not end PENDING`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName("unserialisable")

        post(key, name, retry = true, inTx = true).then().statusCode(500)

        assertEquals(1, WidgetService.runsFor(name))
        assertEquals(0, widgetService.countWidgets(name), "the operation's work must roll back with the status write")
        assertEquals(IdempotencyStatus.RETRYABLE, record(key)!!.status)
    }

    @Test
    fun `in-transaction success without retry - a failed status write rolls the work back and caches ERROR`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName("unserialisable")

        post(key, name, inTx = true).then().statusCode(500)

        assertEquals(0, widgetService.countWidgets(name))
        assertEquals(IdempotencyStatus.ERROR, record(key)!!.status)
    }

    @Test
    fun `default options - the work commits before the status is written (the window in-transaction success closes)`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName("unserialisable")

        post(key, name).then().statusCode(500)

        // 1.0.13 behaviour: the use case committed, then serialising the result failed and ERROR was cached
        assertEquals(1, widgetService.countWidgets(name))
        assertEquals(IdempotencyStatus.ERROR, record(key)!!.status)
    }

    @Test
    fun `unlessRejected without in-transaction success - a failure after the work committed is cached, never retried`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName("unserialisable")

        post(key, name, retry = true).then().statusCode(500)
        assertEquals(1, widgetService.countWidgets(name))
        assertEquals(IdempotencyStatus.ERROR, record(key)!!.status)

        // retrying must not run the operation (and write the widget) a second time
        post(key, name, retry = true).then().statusCode(500)
        assertEquals(1, WidgetService.runsFor(name))
    }

    @Test
    fun `in-transaction success inside a caller's transaction is refused before the record is touched`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName()

        RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", key)
            .body(ExampleCommand(orgId, name))
            .`when`()
            .post("/widgets/in-caller-transaction")
            .then()
            .statusCode(500)

        assertNull(record(key))
        assertEquals(0, WidgetService.runsFor(name))
    }

    @Test
    fun `retryOn alone inside a caller's transaction is refused before the record is touched`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName()

        RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", key)
            .queryParam("retry", true)
            .queryParam("inTx", false)
            .body(ExampleCommand(orgId, name))
            .`when`()
            .post("/widgets/in-caller-transaction")
            .then()
            .statusCode(500)

        assertNull(record(key))
        assertEquals(0, WidgetService.runsFor(name))
        assertEquals(0, widgetService.countWidgets(name))
    }

    @Test
    fun `in-transaction success - work and status commit together`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName()

        val id = post(key, name, retry = true, inTx = true).then().statusCode(200).extract().path<String>("id")
        assertEquals(1, widgetService.countWidgets(name))
        assertEquals(IdempotencyStatus.SUCCESSFUL, record(key)!!.status)

        post(key, name, retry = true, inTx = true).then().statusCode(200).body("id", CoreMatchers.equalTo(id))
        assertEquals(1, WidgetService.runsFor(name))
    }

    @Test
    fun `two concurrent calls with one key - one runs, the other gets 409 (default options)`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName()
        assertOneRunsOtherConflicts(key, name, retry = false, inTx = false)
        assertEquals(1, WidgetService.runsFor(name))
    }

    @Test
    fun `two concurrent calls with one key - one runs, the other gets 409 (retry and in-transaction success)`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName()
        assertOneRunsOtherConflicts(key, name, retry = true, inTx = true)
        assertEquals(1, WidgetService.runsFor(name))
    }

    @Test
    fun `two concurrent retries of a RETRYABLE record - one claims it and runs, the other gets 409`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName()
        failNext(name, 1)
        post(key, name, retry = true).then().statusCode(500)
        assertEquals(IdempotencyStatus.RETRYABLE, record(key)!!.status)

        assertOneRunsOtherConflicts(key, name, retry = true, inTx = true)
        assertEquals(2, WidgetService.runsFor(name))
        assertEquals(IdempotencyStatus.SUCCESSFUL, record(key)!!.status)
    }

    /**
     * Hold the first request inside the operation, send the second, then release the first
     */
    private fun assertOneRunsOtherConflicts(key: String, name: String, retry: Boolean, inTx: Boolean) {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        WidgetService.entered[name] = entered
        WidgetService.gates[name] = gate
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<Response> { post(key, name, retry, inTx) }
            assert(entered.await(10, TimeUnit.SECONDS)) { "first request never reached the operation" }

            post(key, name, retry, inTx).then()
                .statusCode(409)
                .body(CoreMatchers.containsString("IDEMPOTENCY_CONFLICT"))

            gate.countDown()
            first.get(10, TimeUnit.SECONDS).then().statusCode(200)
        } finally {
            gate.countDown()
            WidgetService.gates.remove(name)
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent claims of one RETRYABLE record - exactly one wins at the database`() {
        val key = UUID.randomUUID().toString()
        val hash = "hash-${UUID.randomUUID()}"
        transactions.requiresNew { recordCreator.ensureRecord(key, orgId, hash) }
        repository.markRetryable(key, orgId)

        val threads = 8
        val barrier = CyclicBarrier(threads)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val outcomes = (1..threads).map {
                executor.submit<String> {
                    barrier.await(10, TimeUnit.SECONDS)
                    try {
                        val record = transactions.requiresNew { recordCreator.ensureRecord(key, orgId, hash) }
                        "claimed:${record.status}"
                    } catch (e: IdempotencyConflictException) {
                        "conflict"
                    }
                }
            }.map { it.get(20, TimeUnit.SECONDS) }

            assertEquals(1, outcomes.count { it == "claimed:PENDING" }, outcomes.toString())
            assertEquals(threads - 1, outcomes.count { it == "conflict" }, outcomes.toString())
            assertEquals(IdempotencyStatus.PENDING, record(key)!!.status)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `hash mismatch after success returns IDEMPOTENCY_HASH_MISMATCH and does not run the operation`() {
        val key = UUID.randomUUID().toString()
        post(key, uniqueName(), retry = true).then().statusCode(200)
        assertHashMismatch(key)
    }

    @Test
    fun `hash mismatch after rejection returns IDEMPOTENCY_HASH_MISMATCH and does not run the operation`() {
        val key = UUID.randomUUID().toString()
        post(key, uniqueName("reject"), retry = true).then().statusCode(400)
        assertHashMismatch(key)
    }

    @Test
    fun `hash mismatch after a retryable failure returns IDEMPOTENCY_HASH_MISMATCH and does not run the operation`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName()
        failNext(name, 1)
        post(key, name, retry = true).then().statusCode(500)
        assertHashMismatch(key)
        assertEquals(IdempotencyStatus.RETRYABLE, record(key)!!.status)
    }

    private fun assertHashMismatch(key: String) {
        val before = record(key)!!
        val otherName = uniqueName()
        post(key, otherName, retry = true).then()
            .statusCode(400)
            .body(CoreMatchers.containsString("IDEMPOTENCY_HASH_MISMATCH"))
        assertEquals(0, WidgetService.runsFor(otherName))
        assertEquals(before, record(key))
    }

    @Test
    fun `a RETRYABLE record keeps its original expires_at after a retry and the expiry task deletes it`() {
        val key = UUID.randomUUID().toString()
        val name = uniqueName()
        failNext(name, 2)

        post(key, name, retry = true).then().statusCode(500)
        val originalExpiry = record(key)!!.expiresAt
        assertNotNull(originalExpiry)

        post(key, name, retry = true).then().statusCode(500)
        val afterRetry = record(key)!!
        assertEquals(IdempotencyStatus.RETRYABLE, afterRetry.status)
        assertEquals(originalExpiry, afterRetry.expiresAt)
        assertEquals(2, WidgetService.runsFor(name))

        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE example.idempotency_records SET expires_at = ? WHERE key = ? AND context = ?").use {
                it.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(60)))
                it.setString(2, key)
                it.setString(3, orgId)
                it.executeUpdate()
            }
        }
        expiryTask.run()
        assertNull(record(key))
    }
}
