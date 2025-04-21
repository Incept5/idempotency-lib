package org.incept5.idempotency.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class IdempotencyRecordTest : FunSpec({

    test("should create IdempotencyRecord with default values") {
        val now = Instant.now()
        val record = IdempotencyRecord(
            key = "test-key",
            context = "test-context",
            requestHash = "test-hash",
            status = IdempotencyStatus.PENDING
        )
        
        record.key shouldBe "test-key"
        record.context shouldBe "test-context"
        record.requestHash shouldBe "test-hash"
        record.status shouldBe IdempotencyStatus.PENDING
        record.response shouldBe null
        
        // Check that expiresAt is approximately 14 days after createdAt
        val expectedExpiry = record.createdAt.plus(Duration.ofDays(14))
        val difference = Duration.between(expectedExpiry, record.expiresAt)
        difference.abs().seconds shouldBe 0
    }
    
    test("should create IdempotencyRecord with custom values") {
        val now = Instant.now()
        val expiry = now.plus(Duration.ofDays(30))
        val record = IdempotencyRecord(
            key = "test-key",
            context = "test-context",
            requestHash = "test-hash",
            status = IdempotencyStatus.SUCCESSFUL,
            createdAt = now,
            expiresAt = expiry,
            response = """{"result": "success"}"""
        )
        
        record.key shouldBe "test-key"
        record.context shouldBe "test-context"
        record.requestHash shouldBe "test-hash"
        record.status shouldBe IdempotencyStatus.SUCCESSFUL
        record.createdAt shouldBe now
        record.expiresAt shouldBe expiry
        record.response shouldBe """{"result": "success"}"""
    }
})