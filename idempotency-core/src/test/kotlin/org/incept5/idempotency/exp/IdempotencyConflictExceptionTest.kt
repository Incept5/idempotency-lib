package org.incept5.idempotency.exp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.incept5.error.ErrorCategory

class IdempotencyConflictExceptionTest : FunSpec({
    
    test("IdempotencyConflictException should have correct properties") {
        val exception = IdempotencyConflictException()
        
        exception.category shouldBe ErrorCategory.CONFLICT
        exception.message shouldBe "A request with this Idempotency-Key is already being processed. Please try again later or use a different Idempotency-Key."
        exception.errors.size shouldBe 1
        exception.errors.first().code shouldBe "IDEMPOTENCY_CONFLICT"
        exception.errors.first().field shouldBe "Idempotency-Key header"
    }
})