package org.incept5.idempotency.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.exp.IdempotencyConflictException
import org.incept5.idempotency.repository.IdempotencyRecordRepository
import java.time.Duration
import java.time.Instant

class IdempotencyRecordCreatorTest : FunSpec({

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
    
    val expireAfter = Duration.ofDays(14)
    val creator = IdempotencyRecordCreator(mockRepository, expireAfter)
    
    beforeTest {
        mockRepository.clear()
    }
    
    test("should create a new record when none exists") {
        val key = "test-key"
        val context = "test-context"
        val requestHash = "test-hash"
        
        val record = creator.ensureRecord(key, context, requestHash)
        
        record.key shouldBe key
        record.context shouldBe context
        record.requestHash shouldBe requestHash
        record.status shouldBe IdempotencyStatus.PENDING
        record.response shouldBe null
        
        // Verify expiry is set correctly
        val expectedExpiry = Instant.now().plus(expireAfter)
        val difference = Duration.between(expectedExpiry, record.expiresAt)
        difference.abs().seconds shouldBe 0
        
        // Verify record was stored in repository
        val storedRecord = mockRepository.findRecordByKeyAndContext(key, context)
        storedRecord shouldNotBe null
        storedRecord!!.key shouldBe key
        storedRecord.context shouldBe context
        storedRecord.requestHash shouldBe requestHash
        storedRecord.status shouldBe IdempotencyStatus.PENDING
    }
    
    test("should throw IdempotencyConflictException when record exists with PENDING status") {
        val key = "test-key"
        val context = "test-context"
        val requestHash = "test-hash"
        
        // Create a record first
        creator.ensureRecord(key, context, requestHash)
        
        // Try to create another record with the same key and context
        shouldThrow<IdempotencyConflictException> {
            creator.ensureRecord(key, context, requestHash)
        }
    }
    
    test("should return existing record when it exists with SUCCESSFUL status") {
        val key = "test-key"
        val context = "test-context"
        val requestHash = "test-hash"
        
        // Create a record first
        val initialRecord = creator.ensureRecord(key, context, requestHash)
        
        // Update the record status to SUCCESSFUL
        mockRepository.updateRecordStatus(key, context, IdempotencyStatus.SUCCESSFUL, """{"result": "success"}""")
        
        // Try to ensure record again
        val record = creator.ensureRecord(key, context, requestHash)
        
        // Should return the existing record
        record.key shouldBe key
        record.context shouldBe context
        record.requestHash shouldBe requestHash
        record.status shouldBe IdempotencyStatus.SUCCESSFUL
        record.response shouldBe """{"result": "success"}"""
    }
    
    test("should return existing record when it exists with ERROR status") {
        val key = "test-key"
        val context = "test-context"
        val requestHash = "test-hash"
        
        // Create a record first
        val initialRecord = creator.ensureRecord(key, context, requestHash)
        
        // Update the record status to ERROR
        mockRepository.updateRecordStatus(key, context, IdempotencyStatus.ERROR, """{"error": "test error"}""")
        
        // Try to ensure record again
        val record = creator.ensureRecord(key, context, requestHash)
        
        // Should return the existing record
        record.key shouldBe key
        record.context shouldBe context
        record.requestHash shouldBe requestHash
        record.status shouldBe IdempotencyStatus.ERROR
        record.response shouldBe """{"error": "test error"}"""
    }
})