package org.incept5.idempotency.service

import jakarta.transaction.Transactional
import org.incept5.error.CoreException
import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.exp.IdempotencyConflictException
import org.incept5.idempotency.repository.IdempotencyRecordRepository
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * This service is responsible for creating idempotency records
 * It is important that a new db transaction is used to do this so that we
 * can detect overlapping requests
 */
class IdempotencyRecordCreator(
    private val repository: IdempotencyRecordRepository,
    private val expireAfter: Duration,
    ) {

    private val LOG = LoggerFactory.getLogger(IdempotencyRecordCreator::class.java)
    /**
     * Ensure that an idempotency record exists for the given key and context. If it does not exist, a new record is created
     * @throws CoreException if a record already exists with the same key and context and the status is PENDING
     * @throws SQLException if the transaction commits and there is a unique constraint violation
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun ensureRecord(key: String, context: String, requestHash: String): IdempotencyRecord {
        LOG.debug ( "Ensuring idempotency record for key={} and context= {}}", key, context)
        val record = repository.findRecordByKeyAndContext(key, context)
        return if (record == null) {
            val newRecord = IdempotencyRecord(
                key = key,
                context = context,
                requestHash = requestHash,
status = IdempotencyStatus.PENDING,
                expiresAt = Instant.now().plus(expireAfter)
            )
            LOG.debug ( "Creating new idempotency record for key={} and context= {}}", key, context)
            repository.createRecord(newRecord)
            newRecord
        } else if (record.status == IdempotencyStatus.PENDING) {
            throw IdempotencyConflictException()
        } else {
            // this is interesting and so we log it to info
            LOG.info ("Found existing idempotency record for key={} and context={} so will return the previous result that was recorded at" +
                    " {} with status {} and expires at {}", key, context, record.createdAt, record.status, record.expiresAt)
            record
        }
    }

}
