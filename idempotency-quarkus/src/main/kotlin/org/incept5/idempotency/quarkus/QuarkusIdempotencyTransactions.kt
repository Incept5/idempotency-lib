package org.incept5.idempotency.quarkus

import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.transaction.RollbackException
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

    /**
     * Narayana throws RollbackException from commit when it rolled the transaction back instead, for example
     * because Hibernate's flush in beforeCompletion failed. QuarkusTransaction wraps it, so look along the cause chain.
     */
    override fun isDefiniteRollback(commitFailure: Throwable): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = commitFailure
        while (current != null && seen.add(current)) {
            if (current is RollbackException) return true
            current = current.cause
        }
        return false
    }

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
