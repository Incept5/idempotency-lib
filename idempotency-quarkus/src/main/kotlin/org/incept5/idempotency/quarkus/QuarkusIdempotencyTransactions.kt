package org.incept5.idempotency.quarkus

import io.quarkus.narayana.jta.QuarkusTransaction
import org.incept5.idempotency.service.IdempotencyTransactions

/**
 * IdempotencyTransactions backed by QuarkusTransaction.
 *
 * QuarkusTransaction wraps checked exceptions and Errors thrown by the block in a QuarkusTransactionException;
 * we rethrow the block's own exception instead so that callers (and retry policies) see what the operation threw.
 */
class QuarkusIdempotencyTransactions : IdempotencyTransactions {

    override fun <T> requiresNew(block: () -> T): T {
        return unwrapping(block) { task -> QuarkusTransaction.requiringNew().call(task) }
    }

    override fun isTransactionActive(): Boolean = QuarkusTransaction.isActive()

    private fun <T> unwrapping(block: () -> T, run: (java.util.concurrent.Callable<T>) -> T): T {
        var thrownByBlock: Throwable? = null
        try {
            return run {
                try {
                    block()
                } catch (t: Throwable) {
                    thrownByBlock = t
                    throw t
                }
            }
        } catch (e: Throwable) {
            val original = thrownByBlock
            if (original != null && original !== e) {
                throw original
            }
            throw e
        }
    }
}
