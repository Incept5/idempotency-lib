package org.incept5.idempotency.repository

import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus

interface IdempotencyRecordRepository {

    fun createRecord(record: IdempotencyRecord)
    fun findRecordByKeyAndContext(key: String, context: String): IdempotencyRecord?
    fun updateRecordStatus(key: String, context: String, status: IdempotencyStatus, response: String)

    /**
     * Mark a PENDING record RETRYABLE after a temporary failure. The request hash and expiry are kept
     * and the response is cleared.
     *
     * Only called when a caller opts in with IdempotencyOptions.retryOn. The default throws so that
     * existing custom repositories keep compiling.
     */
    fun markRetryable(key: String, context: String) {
        throw UnsupportedOperationException("${javaClass.name} does not support RETRYABLE records (markRetryable)")
    }

    /**
     * Atomically move a RETRYABLE record back to PENDING, but only if it was created for [requestHash].
     * Must be a single conditional update so that, of two concurrent retries, exactly one wins.
     *
     * @return true if this call claimed the record, false if it was not RETRYABLE (or the hash differed)
     */
    fun claimRetryable(key: String, context: String, requestHash: String): Boolean {
        throw UnsupportedOperationException("${javaClass.name} does not support RETRYABLE records (claimRetryable)")
    }

    fun deleteExpiredRecords(): Int
}
