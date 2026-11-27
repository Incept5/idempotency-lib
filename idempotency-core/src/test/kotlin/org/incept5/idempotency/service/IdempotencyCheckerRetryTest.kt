package org.incept5.idempotency.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.incept5.error.CoreException
import org.incept5.error.Error
import org.incept5.error.ErrorCategory
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.exp.IdempotencyConflictException
import java.time.Duration
import java.time.Instant

class IdempotencyCheckerRetryTest : FunSpec({

    val context = "ctx"
    val hash = "hash-1"
    val retryable = IdempotencyOptions(retryOn = IdempotencyRetry.unlessRejected)
    val retryableInTx = IdempotencyOptions(retryOn = IdempotencyRetry.unlessRejected, successInOperationTransaction = true)
    val inTx = IdempotencyOptions(successInOperationTransaction = true)

    lateinit var repository: InMemoryIdempotencyRepository
    lateinit var work: MutableList<String>
    lateinit var transactions: FakeTransactions
    lateinit var checker: IdempotencyChecker

    fun record() = repository.findRecordByKeyAndContext("key-1", context)!!

    fun rejection(category: ErrorCategory = ErrorCategory.VALIDATION) =
        CoreException(category = category, errors = listOf(Error("REJECTED", "field")), message = "rejected")

    beforeTest {
        repository = InMemoryIdempotencyRepository()
        work = mutableListOf()
        transactions = FakeTransactions(repository, work)
        checker = IdempotencyChecker(IdempotencyRecordCreator(repository, Duration.ofDays(14)), IdempotencyRecordUpdater(repository), transactions)
        checker.idempotencyKey = "key-1"
    }

    test("default options cache a RuntimeException as ERROR and replay it without running the operation (1.0.13 parity)") {
        var runs = 0
        shouldThrow<RuntimeException> {
            checker.ensureIdempotency<String>(context, hash) { runs++; throw RuntimeException("db down") }
        }
        record().status shouldBe IdempotencyStatus.ERROR

        val replayed = shouldThrow<CoreException> {
            checker.ensureIdempotency<String>(context, hash) { runs++; "ok" }
        }
        replayed.category shouldBe ErrorCategory.UNEXPECTED
        runs shouldBe 1
        // the default path does not demarcate transactions itself, exactly as 1.0.13
        transactions.requiresNewCount shouldBe 0
    }

    test("unlessRejected: a RuntimeException leaves the record RETRYABLE, the same request then runs and succeeds, and a third call replays") {
        var runs = 0
        shouldThrow<RuntimeException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { runs++; throw RuntimeException("db down") }
        }
        val afterFailure = record()
        afterFailure.status shouldBe IdempotencyStatus.RETRYABLE
        afterFailure.requestHash shouldBe hash
        afterFailure.response shouldBe null

        checker.ensureIdempotency<String>(context, hash, retryable) { runs++; "ok" } shouldBe "ok"
        record().status shouldBe IdempotencyStatus.SUCCESSFUL

        checker.ensureIdempotency<String>(context, hash, retryable) { runs++; "second" } shouldBe "ok"
        runs shouldBe 2
    }

    test("unlessRejected: a CoreException(VALIDATION) is cached and replayed and the operation is not re-run") {
        var runs = 0
        shouldThrow<CoreException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { runs++; throw rejection() }
        }
        record().status shouldBe IdempotencyStatus.ERROR

        val replayed = shouldThrow<CoreException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { runs++; "ok" }
        }
        replayed.category shouldBe ErrorCategory.VALIDATION
        replayed.errors.first().code shouldBe "REJECTED"
        runs shouldBe 1
    }

    test("unlessRejected treats a rejection carried as a suppressed exception as a rejection") {
        shouldThrow<RuntimeException> {
            checker.ensureIdempotency<String>(context, hash, retryable) {
                throw RuntimeException("wrapper").apply { addSuppressed(rejection(ErrorCategory.NOT_FOUND)) }
            }
        }
        record().status shouldBe IdempotencyStatus.ERROR
        shouldThrow<CoreException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { "ok" }
        }.category shouldBe ErrorCategory.NOT_FOUND
    }

    test("unlessRejected treats a rejection wrapped as a cause as a rejection") {
        var runs = 0
        shouldThrow<java.util.concurrent.CompletionException> {
            checker.ensureIdempotency<String>(context, hash, retryable) {
                runs++
                throw java.util.concurrent.CompletionException(RuntimeException("proxy", rejection()))
            }
        }
        record().status shouldBe IdempotencyStatus.ERROR
        shouldThrow<CoreException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { runs++; "ok" }
        }
        runs shouldBe 1
    }

    test("unlessRejected terminates on a cyclic cause chain") {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        IdempotencyRetry.unlessRejected(a) shouldBe true
    }

    test("a repository without markRetryable support caches a retryable failure as ERROR instead of leaving it PENDING") {
        val legacy = object : org.incept5.idempotency.repository.IdempotencyRecordRepository by repository {
            override fun markRetryable(key: String, context: String) =
                throw UnsupportedOperationException("no RETRYABLE support")
        }
        val legacyChecker = IdempotencyChecker(IdempotencyRecordCreator(legacy, Duration.ofDays(14)), IdempotencyRecordUpdater(legacy), transactions)
        legacyChecker.idempotencyKey = "key-1"
        val thrown = shouldThrow<RuntimeException> {
            legacyChecker.ensureIdempotency<String>(context, hash, retryable) { throw RuntimeException("db down") }
        }
        thrown.message shouldBe "db down"
        thrown.suppressed.toList().shouldBeEmpty()
        record().status shouldBe IdempotencyStatus.ERROR
    }

    test("unlessRejected retries a CoreException in the UNEXPECTED category") {
        shouldThrow<CoreException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { throw rejection(ErrorCategory.UNEXPECTED) }
        }
        record().status shouldBe IdempotencyStatus.RETRYABLE
    }

    test("a retry policy that throws is treated as not retryable and the failure is cached") {
        val brokenPolicy = IdempotencyOptions(retryOn = { throw IllegalStateException("bad policy") })
        val thrown = shouldThrow<RuntimeException> {
            checker.ensureIdempotency<String>(context, hash, brokenPolicy) { throw RuntimeException("db down") }
        }
        thrown.message shouldBe "db down"
        record().status shouldBe IdempotencyStatus.ERROR
    }

    test("a RETRYABLE record keeps its original expires_at after a retry") {
        shouldThrow<RuntimeException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { throw RuntimeException("first") }
        }
        val originalExpiry = record().expiresAt
        originalExpiry shouldNotBe null

        shouldThrow<RuntimeException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { throw RuntimeException("second") }
        }
        record().status shouldBe IdempotencyStatus.RETRYABLE
        record().expiresAt shouldBe originalExpiry
    }

    test("the expiry task deletes an expired RETRYABLE record like any other") {
        shouldThrow<RuntimeException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { throw RuntimeException("first") }
        }
        repository.records["key-1" to context] = record().copy(expiresAt = Instant.now().minusSeconds(1))
        org.incept5.idempotency.tasks.DeleteExpiredIdempotencyRecordsTask(repository).run()
        repository.findRecordByKeyAndContext("key-1", context) shouldBe null
    }

    context("hash mismatch returns IDEMPOTENCY_HASH_MISMATCH and does not run the operation") {
        fun assertMismatch(options: IdempotencyOptions) {
            val statusBefore = record().status
            var ran = false
            val e = shouldThrow<CoreException> {
                checker.ensureIdempotency<String>(context, "different-hash", options) { ran = true; "other" }
            }
            e.errors.first().code shouldBe "IDEMPOTENCY_HASH_MISMATCH"
            ran shouldBe false
            record().status shouldBe statusBefore
            record().requestHash shouldBe hash
        }

        test("after success") {
            checker.ensureIdempotency<String>(context, hash, retryable) { "ok" }
            assertMismatch(retryable)
        }
        test("after rejection") {
            shouldThrow<CoreException> { checker.ensureIdempotency<String>(context, hash, retryable) { throw rejection() } }
            assertMismatch(retryable)
        }
        test("after a retryable failure") {
            shouldThrow<RuntimeException> { checker.ensureIdempotency<String>(context, hash, retryable) { throw RuntimeException("x") } }
            record().status shouldBe IdempotencyStatus.RETRYABLE
            assertMismatch(retryable)
            // also when the second caller uses the default options
            assertMismatch(IdempotencyOptions.DEFAULT)
        }
    }

    test("a RETRYABLE record claimed by another request returns IdempotencyConflictException") {
        shouldThrow<RuntimeException> { checker.ensureIdempotency<String>(context, hash, retryable) { throw RuntimeException("x") } }
        // the other request claims it first
        repository.claimRetryable("key-1", context, hash) shouldBe true
        var ran = false
        shouldThrow<IdempotencyConflictException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { ran = true; "ok" }
        }
        ran shouldBe false
    }

    test("losing the claim to a request that has since succeeded replays its success") {
        shouldThrow<RuntimeException> { checker.ensureIdempotency<String>(context, hash, retryable) { throw RuntimeException("x") } }
        // between our read and our claim, another request claims the record and succeeds
        val racingRepository = object : org.incept5.idempotency.repository.IdempotencyRecordRepository by repository {
            override fun claimRetryable(key: String, context: String, requestHash: String): Boolean {
                repository.claimRetryable(key, context, requestHash)
                repository.updateRecordStatus(key, context, IdempotencyStatus.SUCCESSFUL, "theirs")
                return false
            }
        }
        val racingChecker = IdempotencyChecker(IdempotencyRecordCreator(racingRepository, Duration.ofDays(14)), IdempotencyRecordUpdater(repository), transactions)
        racingChecker.idempotencyKey = "key-1"
        var ran = false
        racingChecker.ensureIdempotency<String>(context, hash, retryable) { ran = true; "mine" } shouldBe "theirs"
        ran shouldBe false
    }

    test("in-transaction success: work and SUCCESSFUL status commit together") {
        checker.ensureIdempotency<String>(context, hash, inTx) { work.add("row"); "ok" } shouldBe "ok"
        work shouldContainExactly listOf("row")
        record().status shouldBe IdempotencyStatus.SUCCESSFUL
    }

    test("in-transaction success: if the status write fails the work rolls back and the key does not end PENDING") {
        repository.failNextUpdateTo = IdempotencyStatus.SUCCESSFUL
        shouldThrow<java.sql.SQLException> {
            checker.ensureIdempotency<String>(context, hash, inTx) { work.add("row"); "ok" }
        }
        work.shouldBeEmpty()
        record().status shouldBe IdempotencyStatus.ERROR
    }

    test("in-transaction success with unlessRejected: a failed status write leaves the key RETRYABLE and a retry succeeds") {
        repository.failNextUpdateTo = IdempotencyStatus.SUCCESSFUL
        shouldThrow<java.sql.SQLException> {
            checker.ensureIdempotency<String>(context, hash, retryableInTx) { work.add("row"); "ok" }
        }
        work.shouldBeEmpty()
        record().status shouldBe IdempotencyStatus.RETRYABLE

        checker.ensureIdempotency<String>(context, hash, retryableInTx) { work.add("row"); "ok" } shouldBe "ok"
        work shouldContainExactly listOf("row")
        record().status shouldBe IdempotencyStatus.SUCCESSFUL
    }

    test("in-transaction success: a failing operation's work rolls back and its failure is written outside the rolled-back transaction") {
        shouldThrow<RuntimeException> {
            checker.ensureIdempotency<String>(context, hash, retryableInTx) { work.add("row"); throw RuntimeException("boom") }
        }
        work.shouldBeEmpty()
        record().status shouldBe IdempotencyStatus.RETRYABLE
    }

    test("in-transaction success without a transaction implementation fails before touching the record") {
        val noTx = IdempotencyChecker(IdempotencyRecordCreator(repository, Duration.ofDays(14)), IdempotencyRecordUpdater(repository))
        noTx.idempotencyKey = "key-1"
        var ran = false
        shouldThrow<IllegalStateException> {
            noTx.ensureIdempotency<String>(context, hash, inTx) { ran = true; "ok" }
        }
        ran shouldBe false
        repository.findRecordByKeyAndContext("key-1", context) shouldBe null
    }

    test("unlessRejected without in-transaction success: a failure after the operation returned is cached, never retried") {
        // the operation's work is done (committed) before the status write, so retrying would run it twice
        repository.failNextUpdateTo = IdempotencyStatus.SUCCESSFUL
        var runs = 0
        shouldThrow<java.sql.SQLException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { runs++; work.add("row"); "ok" }
        }
        work shouldContainExactly listOf("row")
        record().status shouldBe IdempotencyStatus.ERROR

        shouldThrow<CoreException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { runs++; work.add("row"); "ok" }
        }
        runs shouldBe 1
        work shouldContainExactly listOf("row")
    }

    test("in-transaction success inside a caller's transaction fails before touching the record") {
        transactions.callerTransactionActive = true
        var ran = false
        shouldThrow<IllegalStateException> {
            checker.ensureIdempotency<String>(context, hash, retryableInTx) { ran = true; "ok" }
        }
        ran shouldBe false
        repository.findRecordByKeyAndContext("key-1", context) shouldBe null
    }

    test("in-transaction success: an Error from the operation rolls the work back and does not leave the key PENDING") {
        shouldThrow<NotImplementedError> {
            checker.ensureIdempotency<String>(context, hash, retryableInTx) { work.add("row"); TODO("not yet") }
        }
        work.shouldBeEmpty()
        record().status shouldBe IdempotencyStatus.RETRYABLE
    }

    test("if recording the failure fails, the operation's exception is still the one thrown") {
        repository.failNextUpdateTo = IdempotencyStatus.ERROR
        val thrown = shouldThrow<CoreException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { throw rejection() }
        }
        thrown.errors.first().code shouldBe "REJECTED"
        thrown.suppressed.single().message shouldBe "simulated failure writing ERROR"
    }

    test("retryOn without in-transaction success inside a caller's transaction fails before touching the record") {
        transactions.callerTransactionActive = true
        var ran = false
        shouldThrow<IllegalStateException> {
            checker.ensureIdempotency<String>(context, hash, retryable) { ran = true; "ok" }
        }
        ran shouldBe false
        repository.findRecordByKeyAndContext("key-1", context) shouldBe null
    }

    test("default options inside a caller's transaction still run, as in 1.0.13") {
        transactions.callerTransactionActive = true
        checker.ensureIdempotency<String>(context, hash) { "ok" } shouldBe "ok"
        record().status shouldBe IdempotencyStatus.SUCCESSFUL
    }

    test("in-transaction success with unlessRejected: a commit that fails and rolls back is cached as ERROR, never retried") {
        // call 1 creates the record, call 2 runs the operation
        transactions.failCommitOfCall = 2
        var runs = 0
        shouldThrow<IllegalStateException> {
            checker.ensureIdempotency<String>(context, hash, retryableInTx) { runs++; work.add("row"); "ok" }
        }
        work.shouldBeEmpty()
        record().status shouldBe IdempotencyStatus.ERROR
        shouldThrow<CoreException> {
            checker.ensureIdempotency<String>(context, hash, retryableInTx) { runs++; work.add("row"); "ok" }
        }
        runs shouldBe 1
    }

    test("in-transaction success with unlessRejected: a commit that reports failure but committed keeps SUCCESSFUL") {
        // call 1 creates the record, call 2 runs the operation
        transactions.failCommitOfCall = 2
        transactions.commitFailureCommits = true
        var runs = 0
        shouldThrow<IllegalStateException> {
            checker.ensureIdempotency<String>(context, hash, retryableInTx) { runs++; work.add("row"); "ok" }
        }
        work shouldContainExactly listOf("row")
        record().status shouldBe IdempotencyStatus.SUCCESSFUL
        checker.ensureIdempotency<String>(context, hash, retryableInTx) { runs++; "second" } shouldBe "ok"
        runs shouldBe 1
    }

    test("in-transaction success with unlessRejected: a result of the wrong type rolls back and is cached as ERROR, never retried") {
        var runs = 0
        shouldThrow<ClassCastException> {
            checker.ensureIdempotency<Result>(context, hash, retryableInTx) { runs++; work.add("row"); "not a Result" }
        }
        work.shouldBeEmpty()
        record().status shouldBe IdempotencyStatus.ERROR
        shouldThrow<CoreException> {
            checker.ensureIdempotency<Result>(context, hash, retryableInTx) { runs++; Result("a", 1) }
        }
        runs shouldBe 1
    }

    test("a result of the wrong type is cached as ERROR on the default path, as in 1.0.13") {
        shouldThrow<ClassCastException> {
            checker.ensureIdempotency<Result>(context, hash) { "not a Result" }
        }
        record().status shouldBe IdempotencyStatus.ERROR
    }

    test("in-transaction success: a result of the wrong type rolls the work back") {
        shouldThrow<ClassCastException> {
            checker.ensureIdempotency<Result>(context, hash, inTx) { work.add("row"); "not a Result" }
        }
        work.shouldBeEmpty()
        record().status shouldBe IdempotencyStatus.ERROR
    }

    test("complex results replay through JSON with options") {
        checker.ensureIdempotency<Result>(context, hash, retryable) { Result("a", 1) } shouldBe Result("a", 1)
        checker.ensureIdempotency<Result>(context, hash, retryable) { Result("b", 2) } shouldBe Result("a", 1)
    }
})

data class Result(val id: String, val n: Int)
