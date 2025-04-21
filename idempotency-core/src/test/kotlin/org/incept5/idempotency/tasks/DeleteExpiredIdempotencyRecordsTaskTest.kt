package org.incept5.idempotency.tasks

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.repository.IdempotencyRecordRepository
import java.time.Duration
import java.time.Instant
import java.util.*

class DeleteExpiredIdempotencyRecordsTaskTest : FunSpec({

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
                record.expiresAt != null && record.expiresAt!!.isBefore(now)
            }.keys
            expiredKeys.forEach { records.remove(it) }
            return expiredKeys.size
        }
        
        fun clear() {
            records.clear()
        }
        
        fun size(): Int {
            return records.size
        }
    }
    
    val task = DeleteExpiredIdempotencyRecordsTask(mockRepository)
    
    beforeTest {
        mockRepository.clear()
    }
    
    test("should delete expired records") {
        val now = Instant.now()
        
        // Create expired records
        val expiredRecord1 = IdempotencyRecord(
            key = "expired-key-1",
            context = "test-context",
            requestHash = "test-hash",
            status = IdempotencyStatus.SUCCESSFUL,
            expiresAt = now.minus(Duration.ofDays(1))
        )
        
        val expiredRecord2 = IdempotencyRecord(
            key = "expired-key-2",
            context = "test-context",
            requestHash = "test-hash",
            status = IdempotencyStatus.ERROR,
            expiresAt = now.minus(Duration.ofHours(1))
        )
        
        // Create non-expired record
        val validRecord = IdempotencyRecord(
            key = "valid-key",
            context = "test-context",
            requestHash = "test-hash",
            status = IdempotencyStatus.SUCCESSFUL,
            expiresAt = now.plus(Duration.ofDays(7))
        )
        
        mockRepository.createRecord(expiredRecord1)
        mockRepository.createRecord(expiredRecord2)
        mockRepository.createRecord(validRecord)
        
        // Verify initial state
        mockRepository.size() shouldBe 3
        
        // Run the task
        task.run()
        
        // Verify expired records were deleted
        mockRepository.size() shouldBe 1
        mockRepository.findRecordByKeyAndContext("valid-key", "test-context") shouldBe validRecord
        mockRepository.findRecordByKeyAndContext("expired-key-1", "test-context") shouldBe null
        mockRepository.findRecordByKeyAndContext("expired-key-2", "test-context") shouldBe null
    }
    
    test("should handle no expired records") {
        val now = Instant.now()
        
        // Create non-expired records
        val validRecord1 = IdempotencyRecord(
            key = "valid-key-1",
            context = "test-context",
            requestHash = "test-hash",
            status = IdempotencyStatus.SUCCESSFUL,
            expiresAt = now.plus(Duration.ofDays(7))
        )
        
        val validRecord2 = IdempotencyRecord(
            key = "valid-key-2",
            context = "test-context",
            requestHash = "test-hash",
            status = IdempotencyStatus.SUCCESSFUL,
            expiresAt = now.plus(Duration.ofDays(14))
        )
        
        mockRepository.createRecord(validRecord1)
        mockRepository.createRecord(validRecord2)
        
        // Verify initial state
        mockRepository.size() shouldBe 2
        
        // Run the task
        task.run()
        
        // Verify no records were deleted
        mockRepository.size() shouldBe 2
    }
    
    test("should handle empty repository") {
        // Verify initial state
        mockRepository.size() shouldBe 0
        
        // Run the task
        task.run()
        
        // Verify no records were deleted
        mockRepository.size() shouldBe 0
    }
    
    test("should have correct task configuration") {
        // Verify task name
        task.getName() shouldBe "DeleteExpiredIdempotencyRecordsTask"
        
        // Verify frequency configuration
        val frequencyConfig = task.frequency()
        frequencyConfig.isPresent shouldBe true
        frequencyConfig.get().recurs() shouldBe Optional.of(Duration.ofMinutes(10))
        
        // Verify retry configurations are empty
        task.onFailure().isPresent shouldBe false
        task.onIncomplete().isPresent shouldBe false
    }
})
