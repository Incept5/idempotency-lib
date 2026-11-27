package org.incept5.idempotency.domain

import java.time.Duration
import java.time.Instant

/**
 * Keep an Idempotency Record for each POST or PATCH request
 * that supports the Idempotency-Key header
 *
 * A context is mandatory to distinguish between different endpoints
 * and/or different clients/users etc
 *
 * The primary key is the combination of the key and the context
 *
 * On each new request we will check if the record exists and if it is then
 * if the status is PENDING we will return a 409 Conflict
 *
 * If the status is SUCCESSFUL we will return the response from the record
 *
 * If the status is ERROR we will return the same error response as the original request
 *
 * If the status is RETRYABLE the previous attempt failed temporarily (see IdempotencyOptions.retryOn)
 * and the same request may claim the record and run again. The record keeps its request hash, so a
 * different request sent with the key is still rejected.
 *
 * If the record does not exist we will create a new one with the status PENDING but that could
 * cause a db constraint violation if the record is created by another request in the meantime
 * in which case we will return a 409 Conflict
 *
 * If the record exists but the incoming request hash does not match an error is returned
 *
 */
data class IdempotencyRecord(
    val key: String,
    val context: String,
    val requestHash: String,
    val status: IdempotencyStatus,
    val createdAt: Instant = Instant.now(),
    val expiresAt: Instant? = createdAt.plus(Duration.ofDays(14)),
    val response: String? = null,
)

enum class IdempotencyStatus {
    PENDING,
    SUCCESSFUL,
    ERROR,

    /**
     * The last attempt failed in a way the caller marked as temporary, so the same request may run again.
     * Only written when a caller opts in with IdempotencyOptions.retryOn.
     *
     * Rolling deploys: a 1.0.13 (or older) instance cannot parse this value and fails with a 500 for the key
     * before it runs the operation, so nothing is cached or duplicated.
     */
    RETRYABLE
}
