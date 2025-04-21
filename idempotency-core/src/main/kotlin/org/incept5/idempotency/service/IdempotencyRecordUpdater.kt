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
        LOG.debug ("Updating idempotency record for key={}} and context={} setting status=$status and response={}", key, context, status)
        repository.updateRecordStatus(key, context, status, response)
    }
}
