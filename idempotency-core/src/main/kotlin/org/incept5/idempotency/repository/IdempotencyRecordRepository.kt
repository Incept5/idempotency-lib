package org.incept5.idempotency.repository

import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus

interface IdempotencyRecordRepository {

    fun createRecord(record: IdempotencyRecord)
    fun findRecordByKeyAndContext(key: String, context: String): IdempotencyRecord?
    fun updateRecordStatus(key: String, context: String, status: IdempotencyStatus, response: String)

    fun deleteExpiredRecords(): Int
}
