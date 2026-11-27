package org.incept5.idempotency.service

import jakarta.transaction.Transactional
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.repository.IdempotencyRecordRepository
import org.slf4j.LoggerFactory


/**
 * This service is responsible for updating idempotency records.
 *
 * Note: idempotency-quarkus builds this class in a producer method, and CDI does not apply interceptors to producer
 * results, so the @Transactional annotation on [updateRecordStatus] has no effect there (it is kept as it was in 1.0.13).
 * Calls that need a transaction get one from IdempotencyChecker through IdempotencyTransactions.
 */
class IdempotencyRecordUpdater(
    private val repository: IdempotencyRecordRepository,
    ) {

    private val LOG = LoggerFactory.getLogger(IdempotencyRecordUpdater::class.java)
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun updateRecordStatus(key: String, context: String, status: IdempotencyStatus, response: String) {
        LOG.debug ("Updating idempotency record for key={} and context={} setting status={}", key, context, status)
        repository.updateRecordStatus(key, context, status, response)
    }

    /**
     * Write the SUCCESSFUL status in the caller's transaction, so that it commits or rolls back with
     * the operation's own work. The caller (IdempotencyChecker) must have opened that transaction.
     */
    fun updateRecordStatusInCurrentTransaction(key: String, context: String, status: IdempotencyStatus, response: String) {
        LOG.debug ("Updating idempotency record in current transaction for key={} and context={} setting status={}", key, context, status)
        repository.updateRecordStatus(key, context, status, response)
    }

    /**
     * The record's current status, or null if it does not exist
     */
    fun currentStatus(key: String, context: String): IdempotencyStatus? =
        repository.findRecordByKeyAndContext(key, context)?.status

    /**
     * Mark the record RETRYABLE after a temporary failure, keeping its request hash and expiry.
     * IdempotencyChecker runs this in a transaction of its own.
     */
    fun markRetryable(key: String, context: String) {
        LOG.debug ("Marking idempotency record for key={} and context={} as RETRYABLE", key, context)
        repository.markRetryable(key, context)
    }
}
