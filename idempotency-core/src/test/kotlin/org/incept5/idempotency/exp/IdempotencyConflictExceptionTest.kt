package org.incept5.idempotency.exp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.incept5.error.ErrorCategory

class IdempotencyConflictExceptionTest : FunSpec({
    
    test("IdempotencyConflictException should have correct properties") {
        val exception = IdempotencyConflictException()
        
        exception.category shouldBe ErrorCategory.CONFLICT
        exception.message shouldBe "Already processing a request with the same Idempotency-Key and context. Please wait for the previous request to complete and try again."
        exception.errors.size shouldBe 1
        exception.errors.first().code shouldBe "IDEMPOTENCY_CONFLICT"
        exception.errors.first().location shouldBe "Idempotency-Key"
    }
})
