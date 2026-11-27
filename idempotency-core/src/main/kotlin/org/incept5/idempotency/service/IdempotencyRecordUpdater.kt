package org.incept5.idempotency.service

import jakarta.transaction.Transactional
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.repository.IdempotencyRecordRepository
import org.slf4j.LoggerFactory


/**
 * This service is responsible for updating idempotency records
 * in a new transaction so that it is not affected by parent rollbacks
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
     * the operation's own work.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun updateRecordStatusInCurrentTransaction(key: String, context: String, status: IdempotencyStatus, response: String) {
        LOG.debug ("Updating idempotency record in current transaction for key={} and context={} setting status={}", key, context, status)
        repository.updateRecordStatus(key, context, status, response)
    }

    /**
     * Mark the record RETRYABLE after a temporary failure, keeping its request hash and expiry
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun markRetryable(key: String, context: String) {
        LOG.debug ("Marking idempotency record for key={} and context={} as RETRYABLE", key, context)
        repository.markRetryable(key, context)
    }
}
