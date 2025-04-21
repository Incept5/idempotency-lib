package org.incept5.idempotency.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class IdempotencyHasherTest : FunSpec({

    test("should hash a simple string request") {
        val request = "test-request"
        val hash = IdempotencyHasher.hashRequest(request)
        
        // Hash should be a non-empty string
        hash.isNotEmpty() shouldBe true
        
        // Same input should produce same hash
        val hash2 = IdempotencyHasher.hashRequest(request)
        hash shouldBe hash2
    }
    
    test("should hash a complex object request") {
        data class NestedObject(val name: String, val active: Boolean)
        data class TestRequest(val id: String, val value: Int, val nested: NestedObject)

        val request = TestRequest(
            id = "test-id",
            value = 123,
            nested = NestedObject(name = "test-name", active = true)
        )
        
        val hash = IdempotencyHasher.hashRequest(request)
        
        // Hash should be a non-empty string
        hash.isNotEmpty() shouldBe true
        
        // Same input should produce same hash
        val hash2 = IdempotencyHasher.hashRequest(request)
        hash shouldBe hash2
        
        // Different input should produce different hash
        val differentRequest = request.copy(id = "different-id")
        val differentHash = IdempotencyHasher.hashRequest(differentRequest)
        hash shouldNotBe differentHash
    }
    
    test("should produce different hashes for different objects") {
        val request1 = mapOf("key" to "value1")
        val request2 = mapOf("key" to "value2")
        
        val hash1 = IdempotencyHasher.hashRequest(request1)
        val hash2 = IdempotencyHasher.hashRequest(request2)
        
        hash1 shouldNotBe hash2
    }
})
