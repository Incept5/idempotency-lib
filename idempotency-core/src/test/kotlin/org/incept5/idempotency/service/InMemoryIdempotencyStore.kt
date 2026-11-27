package org.incept5.idempotency.service

import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.repository.IdempotencyRecordRepository
import java.sql.SQLException
import java.time.Instant

/**
 * In-memory repository that supports RETRYABLE records, plus a fake transaction manager that rolls back
 * both the idempotency records and the operation's "work" when a joined transaction fails.
 */
class InMemoryIdempotencyRepository : IdempotencyRecordRepository {
    val records = mutableMapOf<Pair<String, String>, IdempotencyRecord>()

    /** set to make the next status write of this status fail */
    var failNextUpdateTo: IdempotencyStatus? = null

    /** how many consecutive writes of [failNextUpdateTo] fail */
    var updateFailures = 1

    /** how many calls of markRetryable fail */
    var markRetryableFailures = 0

    override fun createRecord(record: IdempotencyRecord) {
        if (records.containsKey(record.key to record.context)) {
            throw SQLException("unique constraint violation: idempotency_records_pkey")
        }
        records[record.key to record.context] = record
    }

    override fun findRecordByKeyAndContext(key: String, context: String): IdempotencyRecord? =
        records[key to context]

    override fun updateRecordStatus(key: String, context: String, status: IdempotencyStatus, response: String) {
        if (failNextUpdateTo == status) {
            if (--updateFailures <= 0) failNextUpdateTo = null
            throw SQLException("simulated failure writing $status")
        }
        records[key to context]?.let { records[key to context] = it.copy(status = status, response = response) }
    }

    override fun markRetryable(key: String, context: String) {
        if (markRetryableFailures > 0) {
            markRetryableFailures--
            throw SQLException("simulated failure marking RETRYABLE")
        }
        val record = records[key to context] ?: return
        if (record.status == IdempotencyStatus.PENDING) {
            records[key to context] = record.copy(status = IdempotencyStatus.RETRYABLE, response = null)
        }
    }

    override fun claimRetryable(key: String, context: String, requestHash: String): Boolean {
        val record = records[key to context] ?: return false
        if (record.status != IdempotencyStatus.RETRYABLE || record.requestHash != requestHash) return false
        records[key to context] = record.copy(status = IdempotencyStatus.PENDING)
        return true
    }

    override fun deleteExpiredRecords(): Int {
        val expired = records.filterValues { it.expiresAt?.isBefore(Instant.now()) == true }.keys
        expired.forEach { records.remove(it) }
        return expired.size
    }
}

/**
 * Transactions over the in-memory repository and a list standing in for the operation's own database work.
 * A failed transaction restores both to how they were when it began. Not safe for nested transactions.
 */
class FakeTransactions(
    private val repository: InMemoryIdempotencyRepository,
    private val work: MutableList<String>,
) : IdempotencyTransactions {
    var requiresNewCount = 0

    /** the requiresNew call (counting from 1) whose commit fails; [commitFailureCommits] says whether it committed anyway */
    var failCommitOfCall: Int? = null
    var commitFailureCommits = false
    /** whether a commit failure that rolled back is reported as a definite rollback (as JTA's RollbackException is) */
    var commitFailureIsDefiniteRollback = false

    /** set to simulate a caller that is already in a transaction */
    var callerTransactionActive = false
    private var inTransaction = false

    override fun <T> requiresNew(block: () -> T): T {
        val call = ++requiresNewCount
        val recordsBefore = repository.records.toMap()
        val workBefore = work.toList()
        inTransaction = true
        try {
            val result = block()
            if (failCommitOfCall == call) {
                if (commitFailureCommits) throw CommittedButReportedFailure()
                throw CommitFailedException()
            }
            return result
        } catch (e: CommittedButReportedFailure) {
            // the commit happened: keep its changes
            throw IllegalStateException("simulated commit failure after commit")
        } catch (e: CommitFailedException) {
            repository.records.clear()
            repository.records.putAll(recordsBefore)
            work.clear()
            work.addAll(workBefore)
            if (commitFailureIsDefiniteRollback) throw DefiniteRollback()
            throw IllegalStateException("simulated commit failure, outcome unknown")
        } catch (e: Throwable) {
            repository.records.clear()
            repository.records.putAll(recordsBefore)
            work.clear()
            work.addAll(workBefore)
            throw e
        } finally {
            inTransaction = false
        }
    }

    override fun isTransactionActive(): Boolean = inTransaction || callerTransactionActive

    override fun isDefiniteRollback(commitFailure: Throwable): Boolean = commitFailure is DefiniteRollback
}

/** stands in for jakarta.transaction.RollbackException thrown from commit */
class DefiniteRollback : IllegalStateException("simulated commit failure, rolled back")

private class CommitFailedException : RuntimeException()
private class CommittedButReportedFailure : RuntimeException()
