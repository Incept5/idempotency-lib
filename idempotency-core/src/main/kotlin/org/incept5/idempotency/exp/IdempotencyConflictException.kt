package org.incept5.idempotency.exp

import org.incept5.error.CoreException
import org.incept5.error.Error
import org.incept5.error.ErrorCategory

/**
 * Thrown when we detect overlapping requests with the same idempotency key and context
 **/
class IdempotencyConflictException : CoreException(
    category = ErrorCategory.CONFLICT,
    errors = listOf(Error("IDEMPOTENCY_CONFLICT", "Idempotency-Key")),
    message = "Already processing a request with the same Idempotency-Key and context. Please wait for the previous request to complete and try again."
) {
}
