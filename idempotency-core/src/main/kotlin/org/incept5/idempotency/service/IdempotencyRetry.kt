package org.incept5.idempotency.service

import org.incept5.error.CoreException
import org.incept5.error.ErrorCategory

/**
 * Ready-made retry policies for IdempotencyOptions.retryOn
 */
object IdempotencyRetry {

    /**
     * Never retry: every failure is cached as ERROR and replayed (the 1.0.13 behaviour)
     */
    @JvmField
    val never: (Throwable) -> Boolean = { false }

    /**
     * Retry anything except a deterministic rejection.
     *
     * A CoreException in a 4xx category (anything but UNEXPECTED) is a rejection and stays cached, so the same request
     * gets the same answer. A rejection carried as a suppressed exception counts too, because that is the error
     * ErrorHolder.fromThrowable caches for it. A rejection wrapped as a cause (for example in a CompletionException
     * or a proxy's wrapper exception) counts as well, so it is not re-run on every retry. Everything else (an
     * UNEXPECTED CoreException, any other exception, or a JVM Error such as OutOfMemoryError or StackOverflowError)
     * is treated as temporary. Errors are included deliberately: retryOn is only reached once the operation's work
     * has rolled back, so running it again is safe, whereas caching an Error would pin the key to a 500 for its
     * lifetime. (The default options do not record Errors at all, as in 1.0.13, leaving the key PENDING.)
     */
    @JvmField
    val unlessRejected: (Throwable) -> Boolean = { e -> rejectionIn(e) == null }

    private fun rejectionIn(e: Throwable): CoreException? {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = e
        while (current != null && seen.add(current)) {
            val coreException = current as? CoreException
                ?: current.suppressed.firstOrNull { it is CoreException } as CoreException?
            if (coreException != null && coreException.category != ErrorCategory.UNEXPECTED) {
                return coreException
            }
            current = current.cause
        }
        return null
    }
}
