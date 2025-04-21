package org.incept5.idempotency.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.incept5.error.CoreException
import org.incept5.error.ErrorCategory
import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.exp.IdempotencyConflictException
import org.incept5.idempotency.repository.IdempotencyRecordRepository
import org.incept5.json.Json
import java.sql.SQLException
import java.time.Duration
import java.time.Instant

class IdempotencyCheckerTest : FunSpec({

    val mockRepository = object : IdempotencyRecordRepository {
        private val records = mutableMapOf<Pair<String, String>, IdempotencyRecord>()
        
        override fun createRecord(record: IdempotencyRecord) {
            if (records.containsKey(Pair(record.key, record.context))) {
                throw SQLException("unique constraint violation: idempotency_records_pkey")
            }
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
        
        fun simulateRaceCondition(key: String, context: String, requestHash: String) {
            records[Pair(key, context)] = IdempotencyRecord(
                key = key,
                context = context,
                requestHash = requestHash,
                status = IdempotencyStatus.PENDING
            )
        }
    }
    
    val expireAfter = Duration.ofDays(14)
    val recordCreator = IdempotencyRecordCreator(mockRepository, expireAfter)
    val recordUpdater = IdempotencyRecordUpdater(mockRepository)
    val checker = IdempotencyChecker(recordCreator, recordUpdater)
    
    beforeTest {
        mockRepository.clear()
        checker.idempotencyKey = "test-key"
    }
    
    test("should throw exception when idempotencyKey is null") {
        checker.idempotencyKey = null
        
        val exception = shouldThrow<CoreException> {
            checker.ensureIdempotency<String>("test-context", "test-hash") {
                "test-result"
            }
        }
        
        exception.category shouldBe ErrorCategory.VALIDATION
        exception.message shouldBe "Idempotency-Key header is required for this resource but was missing from the request"
    }
    
    test("should execute operation and store successful result") {
        val key = "test-key"
        val context = "test-context"
        val requestHash = "test-hash"
        val expectedResult = "test-result"
        
        checker.idempotencyKey = key
        
        val result = checker.ensureIdempotency<String>(context, requestHash) {
            expectedResult
        }
        
        result shouldBe expectedResult
        
        // Verify record was created and updated
        val record = mockRepository.findRecordByKeyAndContext(key, context)
        record?.status shouldBe IdempotencyStatus.SUCCESSFUL
        record?.response shouldBe expectedResult
    }
    
    test("should execute operation and store successful result for complex object") {
        data class TestResponse(val id: String, val value: Int)
        
        val key = "test-key"
        val context = "test-context"
        val requestHash = "test-hash"
        val expectedResult = TestResponse(id = "test-id", value = 123)
        
        checker.idempotencyKey = key
        
        val result = checker.ensureIdempotency<TestResponse>(context, requestHash) {
            expectedResult
        }
        
        result shouldBe expectedResult
        
        // Verify record was created and updated
        val record = mockRepository.findRecordByKeyAndContext(key, context)
        record?.status shouldBe IdempotencyStatus.SUCCESSFUL
        record?.response shouldBe Json.toJson(expectedResult)
    }
    
    test("should return previous successful result without executing operation") {
        val key = "test-key"
        val context = "test-context"
        val requestHash = "test-hash"
        val expectedResult = "test-result"
        
        // First call to store the result
        checker.idempotencyKey = key
        checker.ensureIdempotency<String>(context, requestHash) {
            expectedResult
        }
        
        // Second call should return the stored result without executing the operation
        var operationExecuted = false
        val result = checker.ensureIdempotency<String>(context, requestHash) {
            operationExecuted = true
            "new-result"
        }
        
        result shouldBe expectedResult
        operationExecuted shouldBe false
    }
    
    test("should throw same error for previous failed operation") {
        val key = "test-key"
        val context = "test-context"
        val requestHash = "test-hash"
        
        // First call to store the error
        checker.idempotencyKey = key
        val originalException = shouldThrow<CoreException> {
            checker.ensureIdempotency<String>(context, requestHash) {
                throw CoreException(
                    category = ErrorCategory.VALIDATION,
                    errors = listOf(org.incept5.error.Error("TEST_ERROR", "test-field")),
                    message = "Test error message"
                )
            }
        }
        
        // Second call should throw the same error without executing the operation
        var operationExecuted = false
        val exception = shouldThrow<CoreException> {
            checker.ensureIdempotency<String>(context, requestHash) {
                operationExecuted = true
                "new-result"
            }
        }
        
        exception.category shouldBe originalException.category
        exception.message shouldBe originalException.message
        exception.errors.first().code shouldBe originalException.errors.first().code
        operationExecuted shouldBe false
    }
    
    test("should throw IdempotencyConflictException when race condition occurs") {
        val key = "test-key"
        val context = "test-context"
        val requestHash = "test-hash"
        
        // Simulate a race condition by creating a record with PENDING status
        mockRepository.simulateRaceCondition(key, context, requestHash)
        
        // Should throw IdempotencyConflictException
        shouldThrow<IdempotencyConflictException> {
            checker.ensureIdempotency<String>(context, requestHash) {
                "test-result"
            }
        }
    }
    
    test("should throw error when request hash doesn't match") {
        val key = "test-key"
        val context = "test-context"
        val originalHash = "original-hash"
        val newHash = "new-hash"
        
        // First call with original hash
        checker.idempotencyKey = key
        checker.ensureIdempotency<String>(context, originalHash) {
            "test-result"
        }
        
        // Second call with different hash should throw error
        val exception = shouldThrow<CoreException> {
            checker.ensureIdempotency<String>(context, newHash) {
                "new-result"
            }
        }
        
        exception.category shouldBe ErrorCategory.VALIDATION
        exception.message shouldBe "The Idempotency-Key has previously been used for a different request. Please try again with a new Idempotency-Key header."
    }
})