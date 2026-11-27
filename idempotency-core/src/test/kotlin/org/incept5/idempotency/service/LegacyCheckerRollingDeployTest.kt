package org.incept5.idempotency.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.repository.IdempotencyRecordRepository
import java.time.Duration

/**
 * Documents the rolling-deploy window: an instance still on 1.0.13 reads a RETRYABLE record written by a newer one.
 *
 * 1.0.13's SqlIdempotencyRecordRepository maps the status column with IdempotencyStatus.valueOf, and its enum has
 * no RETRYABLE. This simulates that read and shows the request fails before the operation runs and nothing is
 * written, so nothing is cached or duplicated.
 */
class LegacyCheckerRollingDeployTest : FunSpec({

    // IdempotencyStatus as released in 1.0.13
    val legacyStatuses = listOf("PENDING", "SUCCESSFUL", "ERROR")

    test("the 1.0.13 status enum cannot parse RETRYABLE") {
        legacyStatuses shouldBe Legacy1013Status.entries.map { it.name }
        shouldThrow<IllegalArgumentException> { Legacy1013Status.valueOf(IdempotencyStatus.RETRYABLE.name) }
    }

    test("a 1.0.13 checker reading a RETRYABLE record fails before running the operation and writes nothing") {
        // the row as a newer instance left it, stored as the raw status string
        val rows = mutableMapOf(("key-1" to "ctx") to Pair("hash-1", "RETRYABLE"))
        var writes = 0

        val legacyRepository = object : IdempotencyRecordRepository {
            override fun createRecord(record: IdempotencyRecord) { writes++ }
            override fun findRecordByKeyAndContext(key: String, context: String): IdempotencyRecord? {
                val (hash, status) = rows[key to context] ?: return null
                // what 1.0.13 does with the status column
                val legacy = Legacy1013Status.valueOf(status)
                return IdempotencyRecord(key, context, hash, IdempotencyStatus.valueOf(legacy.name))
            }
            override fun updateRecordStatus(key: String, context: String, status: IdempotencyStatus, response: String) { writes++ }
            override fun deleteExpiredRecords(): Int = 0
        }

        // the default options reproduce the 1.0.13 checker
        val checker = IdempotencyChecker(IdempotencyRecordCreator(legacyRepository, Duration.ofDays(14)), IdempotencyRecordUpdater(legacyRepository))
        checker.idempotencyKey = "key-1"
        var ran = false
        shouldThrow<IllegalArgumentException> {
            checker.ensureIdempotency<String>("ctx", "hash-1") { ran = true; "ok" }
        }
        ran shouldBe false
        writes shouldBe 0
        rows["key-1" to "ctx"] shouldBe Pair("hash-1", "RETRYABLE")
    }
})

/** IdempotencyStatus exactly as released in 1.0.13. Do not add RETRYABLE here. */
private enum class Legacy1013Status { PENDING, SUCCESSFUL, ERROR }
