package org.incept5.idempotency.quarkus

import org.incept5.idempotency.service.IdempotencyChecker
import io.quarkus.logging.Log
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * This request filter should intercept all requests and check for the presence of the Idempotency-Key header
 * and if it is present, it will just set it on the IdempotencyChecker request scoped bean
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
class IdempotencyKeyFilter (
    private val idempotencyChecker: IdempotencyChecker
) : ContainerRequestFilter {

    override fun filter(requestContext: ContainerRequestContext) {
        val idempotencyKey = requestContext.getHeaderString("Idempotency-Key")
        if (idempotencyKey != null) {
            Log.debug("Setting idempotency key $idempotencyKey on request scoped IdempotencyChecker")
            idempotencyChecker.idempotencyKey = idempotencyKey
        }
    }
}
