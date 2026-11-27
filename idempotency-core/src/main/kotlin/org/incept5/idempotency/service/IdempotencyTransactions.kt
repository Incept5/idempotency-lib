package org.incept5.idempotency.service

/**
 * How the IdempotencyChecker demarcates transactions.
 *
 * The record creator and updater are plain classes, and a CDI container does not apply interceptors such as
 * @Transactional to instances returned from producer methods, so the checker asks this interface for transactions
 * explicitly. The Quarkus module supplies an implementation backed by QuarkusTransaction.
 *
 * Only used by calls that opt in through IdempotencyOptions; the default options run exactly as 1.0.13 did.
 */
interface IdempotencyTransactions {

    /**
     * Run [block] in a new transaction, suspending any current one, and commit before returning. If [block] throws,
     * the transaction is rolled back and the exception [block] threw is rethrown unchanged.
     */
    fun <T> requiresNew(block: () -> T): T

    /**
     * True if the calling thread is in a transaction
     */
    fun isTransactionActive(): Boolean

    companion object {
        /**
         * No transaction management: every statement auto-commits, as the 1.0.13 library effectively ran.
         * Cannot write the success status in the operation's transaction.
         */
        val NONE: IdempotencyTransactions = object : IdempotencyTransactions {
            override fun <T> requiresNew(block: () -> T): T = block()

            override fun isTransactionActive(): Boolean = false
        }
    }
}
