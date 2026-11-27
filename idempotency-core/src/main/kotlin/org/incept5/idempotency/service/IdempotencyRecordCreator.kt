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
 *
 * Note: idempotency-quarkus builds this class in a producer method, and CDI does not apply interceptors to producer
 * results, so the @Transactional annotation on [ensureRecord] has no effect there (it is kept as it was in 1.0.13).
 * With default options each statement auto-commits, or joins the caller's transaction; with non-default options
 * IdempotencyChecker runs ensureRecord in a new transaction through IdempotencyTransactions.
 */
class IdempotencyRecordCreator(
    private val repository: IdempotencyRecordRepository,
    private val expireAfter: Duration,
    ) {

    private val LOG = LoggerFactory.getLogger(IdempotencyRecordCreator::class.java)
    /**
     * Ensure that an idempotency record exists for the given key and context. If it does not exist, a new record is created
     *
     * If the record is RETRYABLE and was created for the same [requestHash] it is claimed (moved back to PENDING with a
     * single conditional update) and returned as PENDING, which tells the caller to run the operation again.
     * A RETRYABLE record for a different request is returned unchanged so the caller rejects it as a hash mismatch.
     *
     * @throws CoreException if a record already exists with the same key and context and the status is PENDING
     * @throws SQLException if the transaction commits and there is a unique constraint violation
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun ensureRecord(key: String, context: String, requestHash: String): IdempotencyRecord {
        LOG.debug ( "Ensuring idempotency record for key={} and context={}", key, context)
        val record = repository.findRecordByKeyAndContext(key, context)
        return if (record == null) {
            val newRecord = IdempotencyRecord(
                key = key,
                context = context,
                requestHash = requestHash,
                status = IdempotencyStatus.PENDING,
                expiresAt = Instant.now().plus(expireAfter)
            )
            LOG.debug ( "Creating new idempotency record for key={} and context={}", key, context)
            repository.createRecord(newRecord)
            newRecord
        } else if (record.status == IdempotencyStatus.PENDING) {
            throw IdempotencyConflictException()
        } else if (record.status == IdempotencyStatus.RETRYABLE && record.requestHash == requestHash) {
            claimRetryable(record)
        } else {
            // this is interesting and so we log it to info
            LOG.info ("Found existing idempotency record for key={} and context={} so will return the previous result that was recorded at" +
                    " {} with status {} and expires at {}", key, context, record.createdAt, record.status, record.expiresAt)
            record
        }
    }

    private fun claimRetryable(record: IdempotencyRecord): IdempotencyRecord {
        if (repository.claimRetryable(record.key, record.context, record.requestHash)) {
            LOG.info ("Claimed retryable idempotency record for key={} and context={} so will run the request again", record.key, record.context)
            return record.copy(status = IdempotencyStatus.PENDING, response = null)
        }
        // somebody else changed the record between our read and our claim: handle what is there now
        val current = repository.findRecordByKeyAndContext(record.key, record.context)
        return when (current?.status) {
            // another request claimed it first (PENDING), or claimed it and failed again (RETRYABLE),
            // or the record expired and was deleted: in every case the caller should try again later
            null, IdempotencyStatus.PENDING, IdempotencyStatus.RETRYABLE -> throw IdempotencyConflictException()
            // it has since completed so replay that outcome
            else -> current
        }
    }

}
