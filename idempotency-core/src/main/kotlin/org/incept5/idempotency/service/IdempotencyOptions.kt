package org.incept5.idempotency.service

/**
 * Per-call options for IdempotencyChecker.ensureIdempotency.
 *
 * [DEFAULT] reproduces 1.0.13: every failure is cached as ERROR and replayed, and the SUCCESSFUL status is written
 * in its own transaction after the operation returns.
 *
 * @param retryOn decides whether an exception thrown by the operation is temporary. When it returns true the record is
 * marked RETRYABLE instead of being cached as ERROR, and the same request (same key and request hash) may run the
 * operation again. A failure after the operation has returned outside the checker's transaction (serialising the
 * result, writing the status) is always cached, because the operation's work has already committed. Any non-default
 * options are refused (IllegalStateException) when called inside a transaction. Only safe when a failure leaves no side effect: the operation's work rolled back and no external call
 * (e.g. to a payment processor) was made. See [IdempotencyRetry] for ready-made predicates.
 *
 * @param successInOperationTransaction when true, the operation and the SUCCESSFUL status write run in one
 * transaction that the checker opens; calling from inside a transaction is refused. The operation's work commits if
 * and only if the key becomes SUCCESSFUL. A @Transactional (REQUIRED) use case called from the operation joins this
 * transaction, so its work now commits after the result has been serialised, at the end of ensureIdempotency.
 * Do not use for operations that must commit part of their work before an external call (e.g. persisting state
 * before calling a payment processor): joining would hold all of it in one transaction across that call.
 */
data class IdempotencyOptions(
    val retryOn: (Throwable) -> Boolean = IdempotencyRetry.never,
    val successInOperationTransaction: Boolean = false,
) {
    /**
     * True when these options behave exactly like 1.0.13 and so need no transaction management.
     * Compares retryOn by identity: pass IdempotencyRetry.never (or omit it), not an equivalent lambda.
     */
    val isDefault: Boolean
        get() = retryOn === IdempotencyRetry.never && !successInOperationTransaction

    companion object {
        @JvmField
        val DEFAULT = IdempotencyOptions()
    }
}
