package org.incept5.idempotency.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.repository.IdempotencyRecordRepository
import java.time.Instant

class IdempotencyRecordUpdaterTest : FunSpec({

    val mockRepository = object : IdempotencyRecordRepository {
        private val records = mutableMapOf<Pair<String, String>, IdempotencyRecord>()
        
        override fun createRecord(record: IdempotencyRecord) {
            records[Pair(record.key, record.context)] = record
        }
        
        override fun findRecordByKeyAndContext(key: String, context: String): IdempotencyRecord? {
            return records[Pair(key, context)]
        }
        
        override fun updateRecordStatus(key: String, context: String, status: IdempotencyStatus, response: String) {
            val record = records[Pair(key, context)]
            if (record != null) {
                records[Pair(key, context)] = record.copy(status = status, response = response)
            }
        }
        
        override fun deleteExpiredRecords(): Int {
            val now = Instant.now()
            val expiredKeys = records.filter { (_, record) -> 
                record.expiresAt != null && record.expiresAt.isBefore(now) 
            }.keys
            expiredKeys.forEach { records.remove(it) }
            return expiredKeys.size
        }
        
        fun clear() {
            records.clear()
        }
    }
    
    val updater = IdempotencyRecordUpdater(mockRepository)
    
    beforeTest {
        mockRepository.clear()
    }
    
    test("should update record status to SUCCESSFUL") {
        val key = "test-key"
        val context = "test-context"
        val requestHash = "test-hash"
        val initialRecord = IdempotencyRecord(
            key = key,
            context = context,
            requestHash = requestHash,
            status = IdempotencyStatus.PENDING
        )
        
        // Create initial record
        mockRepository.createRecord(initialRecord)
        
        // Update the record status
        val response = """{"result": "success"}"""
        updater.updateRecordStatus(key, context, IdempotencyStatus.SUCCESSFUL, response)
        
        // Verify record was updated
        val updatedRecord = mockRepository.findRecordByKeyAndContext(key, context)
        updatedRecord shouldBe initialRecord.copy(
            status = IdempotencyStatus.SUCCESSFUL,
            response = response
        )
    }
    
    test("should update record status to ERROR") {
        val key = "test-key"
        val context = "test-context"
        val requestHash = "test-hash"
        val initialRecord = IdempotencyRecord(
            key = key,
            context = context,
            requestHash = requestHash,
            status = IdempotencyStatus.PENDING
        )
        
        // Create initial record
        mockRepository.createRecord(initialRecord)
        
        // Update the record status
        val response = """{"error": "test error"}"""
        updater.updateRecordStatus(key, context, IdempotencyStatus.ERROR, response)
        
        // Verify record was updated
        val updatedRecord = mockRepository.findRecordByKeyAndContext(key, context)
        updatedRecord shouldBe initialRecord.copy(
            status = IdempotencyStatus.ERROR,
            response = response
        )
    }
})