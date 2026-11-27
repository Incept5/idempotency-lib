package org.incept5.idempotency.service

import org.incept5.error.CoreException
import org.incept5.error.Error
import org.incept5.error.ErrorCategory
import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.exp.IdempotencyConflictException
import org.incept5.json.Json
import org.slf4j.LoggerFactory
import java.sql.SQLException


/**
 * This class is responsible for ensuring idempotency of requests
 *
 * usage:
 *
 * inject the request scoped IdempotencyChecker into your service
 * and then wrap your call to the service layer with the ensureIdempotency method like:
 *
 * val requestHash = IdempotencyHasher.hashRequest(request)
 *
 * val result = idempotencyChecker.ensureIdempotency<YourResponseType>("my-context", requestHash) {
 *     // your service call here
 *     myServiceLayer.doSomething(request)
 * }
 *
 * To let temporary failures be retried rather than cached, pass options:
 *
 * val result = idempotencyChecker.ensureIdempotency<YourResponseType>("my-context", requestHash,
 *     IdempotencyOptions(retryOn = IdempotencyRetry.unlessRejected)) {
 *     myServiceLayer.doSomething(request)
 * }
 *
 * the context parameter is used to namespace the idempotency records and ensure that one client
 * doesn't tangle up with another clients records. So it should be set to something like the organization id,
 * merchant id, user id, client id etc
 *
 * If the requestHash does not match but the idempotent key does then an error is thrown
 *
 */
class IdempotencyChecker @JvmOverloads constructor(
    val recordCreator: IdempotencyRecordCreator,
    val recordUpdater: IdempotencyRecordUpdater,
    val transactions: IdempotencyTransactions = IdempotencyTransactions.NONE,
) {

    private val LOG = LoggerFactory.getLogger(IdempotencyChecker::class.java)

    private companion object {
        /** pauses before each further attempt to record a failure (non-default options only) */
        val FAILURE_WRITE_BACKOFF_MILLIS = longArrayOf(50, 250)
    }

    // this will get set by the filter from Idempotency-Key header
    var idempotencyKey: String? = null

    /**
     * Run [operation] at most once per Idempotency-Key and context, with the 1.0.13 behaviour:
     * every failure is cached as ERROR and replayed.
     */
    inline fun <reified T> ensureIdempotency(context: String, requestHash: String, noinline operation: () -> Any): T {
        return ensureIdempotency(context, requestHash, IdempotencyOptions.DEFAULT, T::class.java, operation)
    }

    /**
     * Run [operation] at most once per Idempotency-Key and context, with per-call [options]
     * (retryable failures, success written in the operation's transaction).
     */
    inline fun <reified T> ensureIdempotency(
        context: String,
        requestHash: String,
        options: IdempotencyOptions,
        noinline operation: () -> Any,
    ): T {
        return ensureIdempotency(context, requestHash, options, T::class.java, operation)
    }

    fun <T> ensureIdempotency(
        context: String,
        requestHash: String,
        options: IdempotencyOptions,
        resultType: Class<T>,
        operation: () -> Any,
    ): T {
        val key = idempotencyKey ?: throw CoreException(
            category = ErrorCategory.VALIDATION,
            errors = listOf(Error("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header")),
            message = "Idempotency-Key header is required for this resource but was missing from the request"
        )

        // fail before touching the record, so a misconfiguration does not cache an error for the key
        if (options.successInOperationTransaction) {
            check(transactions !== IdempotencyTransactions.NONE) {
                "IdempotencyOptions.successInOperationTransaction needs an IdempotencyTransactions implementation " +
                    "(the idempotency-quarkus module provides one)"
            }
        }
        if (!options.isDefault) {
            // non-default options write the record's status in transactions of their own. Inside a caller's transaction
            // the operation's work would only commit (or roll back) when the caller's does, after a SUCCESSFUL or
            // RETRYABLE status had already committed: a later rollback would replay a success for work that never
            // happened, or let a retry repeat work that did
            check(!transactions.isTransactionActive()) {
                "IdempotencyOptions other than the defaults must be used outside a transaction: the checker controls " +
                    "the transactions the operation and its status run in. Remove @Transactional from the caller."
            }
        }

        val resultIsString = resultType == String::class.java
        val record : IdempotencyRecord
        try {
            // this call will throw an exception if the request is already being processed
            // (or, for a RETRYABLE record, claims it so that we run the request again)
            record = isolated(options) { recordCreator.ensureRecord(key, context, requestHash) }
        }
        catch (e: Exception) {
            if (isDuplicateKey(e)) {
                // eek - race condition on 2 competing requests and we lost as we committed second
                throw IdempotencyConflictException()
            }
            throw e
        }

        // check the requestHash is the same otherwise throw an error
        if (record.requestHash != requestHash) {
            throw CoreException(
                category = ErrorCategory.VALIDATION,
                errors = listOf(Error("IDEMPOTENCY_HASH_MISMATCH", "Idempotency-Key header")),
                message = "The Idempotency-Key has previously been used for a different request. Please try again with a new Idempotency-Key header."
            )
        }

        when (record.status) {
            IdempotencyStatus.SUCCESSFUL -> {
                // we have already processed this request so return the previous successful response
                @Suppress("UNCHECKED_CAST")
                return if (resultIsString) {
                    // response is a string and required type is a string so just return it
                    record.response as T
                } else {
                    // response is a json string and required type is a class so marshal it
                    Json.fromJson(record.response!!, resultType)
                }
            }
            IdempotencyStatus.ERROR -> {
                val error = Json.fromJson(record.response!!, ErrorHolder::class.java)
                // we have already processed this request and it failed so return the same error
                throw CoreException(
                    category = error.category,
                    errors = listOf(error.error),
                    message = error.message
                )
            }
            // a RETRYABLE record for this request is claimed by ensureRecord, so we should never see one here
            IdempotencyStatus.RETRYABLE -> throw IdempotencyConflictException()
            IdempotencyStatus.PENDING -> {
                // we are processing this request ourselves so we need to capture
                // the result and store it in the db and update the status
            }
        }

        // where we are, so that a failure is only offered a retry when running the operation again is safe
        var stage = Stage.RUNNING_OPERATION
        try {
            val result = if (options.successInOperationTransaction) {
                // the operation's work and the SUCCESSFUL status commit (or roll back) together
                transactions.requiresNew {
                    val result = operation()
                    // a result of the wrong type (or one that cannot be serialised) rolls the work back rather than
                    // committing it under an ERROR
                    stage = Stage.CONVERTING_RESULT
                    castResult(result, resultType)
                    val response = serialise(result, resultIsString)
                    stage = Stage.WRITING_STATUS_IN_TRANSACTION
                    recordUpdater.updateRecordStatusInCurrentTransaction(key, context, IdempotencyStatus.SUCCESSFUL, response)
                    stage = Stage.COMMITTING
                    result
                }
            } else {
                val result = operation()
                stage = Stage.OPERATION_COMMITTED
                val response = serialise(result, resultIsString)
                isolated(options) { recordUpdater.updateRecordStatus(key, context, IdempotencyStatus.SUCCESSFUL, response) }
                result
            }
            return castResult(result, resultType)
        }
        catch (e: Throwable) {
            if (options.isDefault) {
                // exactly as 1.0.13: Errors are not recorded, and a failure to record propagates
                if (e !is Exception) throw e
                recordFailure(key, context, options, e, retryAllowed = false)
                throw e
            }
            var retryAllowed = stage.retryAllowed
            if (stage == Stage.COMMITTING) {
                if (commitMayHaveSucceeded(key, context)) {
                    // the commit failed with an unclear outcome and the SUCCESSFUL status (and so the work) is visible:
                    // it did commit. Leave it, so the client's retry replays the success
                    LOG.warn("Commit reported a failure but the SUCCESSFUL status committed for key={} and context={}", key, context, e)
                    throw e
                }
                // An ORM flushes at commit, so this is where most database errors in the operation's work surface.
                // When the transaction manager says it rolled back, the work went with it and running again is as safe
                // as after a failure inside the operation. An unknown outcome is still never retried.
                retryAllowed = transactions.isDefiniteRollback(e)
            }
            recordFailureWithRetries(key, context, options, e, retryAllowed = retryAllowed)
            throw e
        }
    }

    /**
     * Where ensureIdempotency was when something failed, and whether running the operation again is then safe
     */
    private enum class Stage(val retryAllowed: Boolean) {
        /** the operation itself failed: its work did not commit (or, inside our transaction, rolled back) */
        RUNNING_OPERATION(true),
        /** the result has the wrong type or cannot be serialised: deterministic, so running again would fail again */
        CONVERTING_RESULT(false),
        /** the SUCCESSFUL write inside our transaction failed: the work rolled back with it */
        WRITING_STATUS_IN_TRANSACTION(true),
        /**
         * our transaction failed to commit: only retried when the transaction manager reports a definite rollback
         * (IdempotencyTransactions.isDefiniteRollback), because otherwise the work may have committed
         */
        COMMITTING(false),
        /** the operation returned outside a transaction we control: its work is committed */
        OPERATION_COMMITTED(false),
    }

    /**
     * After a failed commit, whether the record shows that the commit actually happened. If the record cannot be
     * read, assume it may have, and cache nothing: the key stays PENDING rather than risking an ERROR over a success.
     */
    private fun commitMayHaveSucceeded(key: String, context: String): Boolean {
        return try {
            transactions.requiresNew { recordUpdater.currentStatus(key, context) } != IdempotencyStatus.PENDING
        } catch (readFailure: Exception) {
            LOG.error("Could not read the idempotency record for key={} and context={} after a failed commit, so it " +
                "stays PENDING (409 until it expires)", key, context, readFailure)
            true
        }
    }

    /**
     * recordFailure for non-default options, retried a few times so that a brief database outage (often the very
     * failure retryOn is for) does not leave the key PENDING, answering 409 until it expires. The operation's
     * exception is always the one thrown; a write that still fails is attached to it as suppressed.
     */
    private fun recordFailureWithRetries(key: String, context: String, options: IdempotencyOptions, e: Throwable, retryAllowed: Boolean) {
        var attempt = 0
        while (true) {
            try {
                recordFailure(key, context, options, e, retryAllowed)
                return
            } catch (recordingFailure: Throwable) {
                if (recordingFailure !is Exception || attempt >= FAILURE_WRITE_BACKOFF_MILLIS.size) {
                    LOG.error("Could not record the failure for key={} and context={}, so the key stays PENDING " +
                        "(409 until it expires)", key, context, recordingFailure)
                    e.addSuppressed(recordingFailure)
                    return
                }
                LOG.warn("Recording the failure for key={} and context={} failed, retrying", key, context, recordingFailure)
                try {
                    Thread.sleep(FAILURE_WRITE_BACKOFF_MILLIS[attempt++])
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    e.addSuppressed(recordingFailure)
                    return
                }
            }
        }
    }

    /**
     * Store the outcome of a failed operation: RETRYABLE when that is allowed and the caller's policy says the failure
     * is temporary, otherwise ERROR so that it is replayed for future requests
     */
    private fun recordFailure(key: String, context: String, options: IdempotencyOptions, e: Throwable, retryAllowed: Boolean) {
        val retryable = retryAllowed && try {
            options.retryOn(e)
        } catch (policyFailure: Exception) {
            LOG.warn("IdempotencyOptions.retryOn threw for key={} and context={}, so caching the failure as ERROR", key, context, policyFailure)
            false
        }
        if (retryable) {
            try {
                isolated(options) { recordUpdater.markRetryable(key, context) }
                return
            } catch (unsupported: Throwable) {
                if (!isUnsupported(unsupported)) throw unsupported
                // a custom repository without RETRYABLE support: cache the failure as ERROR rather than leave the
                // key PENDING (and every retry a 409) until it expires
                LOG.warn("Repository does not support markRetryable, so caching the failure as ERROR for key={} and context={}", key, context, unsupported)
            }
        }
        val error = if (e is CoreException) {
            // operation threw a CoreException which we need to store in the db for future requests
            ErrorHolder.fromCoreException(e)
        } else {
            // operation threw an unexpected exception which we need to store in the db for future requests
            ErrorHolder.fromThrowable(e)
        }
        isolated(options) { recordUpdater.updateRecordStatus(key, context, IdempotencyStatus.ERROR, Json.toJson(error)) }
    }

    /**
     * A checked cast, as the reified `result as T` of 1.0.13 was
     */
    private fun <T> castResult(result: Any, resultType: Class<T>): T {
        // primitive types (e.g. ensureIdempotency<Int>) arrive boxed, so only check reference types
        @Suppress("UNCHECKED_CAST")
        return if (resultType.isPrimitive) result as T else resultType.cast(result)
    }

    /**
     * Run a record read/write in its own transaction. The default options skip this so they behave exactly as 1.0.13.
     */
    private fun <R> isolated(options: IdempotencyOptions, block: () -> R): R {
        return if (options.isDefault) block() else transactions.requiresNew(block)
    }

    private fun serialise(result: Any, resultIsString: Boolean): String {
        return if (resultIsString) result as String else Json.toJson(result)
    }

    private fun isUnsupported(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is UnsupportedOperationException) return true
            cause = cause.cause.takeIf { it !== cause }
        }
        return false
    }

    private fun isDuplicateKey(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            val message = cause.message
            if (cause is SQLException && message != null && message.contains("unique constraint") && message.contains("idempotency_records_pkey")) {
                return true
            }
            cause = cause.cause.takeIf { it !== cause }
        }
        return false
    }

    data class ErrorHolder(val category: ErrorCategory, val error: Error, val message: String) {

        companion object {
            fun fromCoreException(e: CoreException): ErrorHolder {
                return ErrorHolder(e.category, e.errors.first(), e.message ?: "An error occurred")
            }

            fun fromThrowable(e: Throwable): ErrorHolder {
                if ( e.suppressed != null && e.suppressed.isNotEmpty() ){
                    val suppressedCoreException = e.suppressed.find { it is CoreException } as CoreException?
                    if (suppressedCoreException != null) {
                        return fromCoreException(suppressedCoreException)
                    }
                }
                return ErrorHolder(
                    category = ErrorCategory.UNEXPECTED,
                    error = Error(code = "UNEXPECTED"),
                    message = e.message ?: "Idempotent Request failed previously so returning the same error as before"
                )
            }
        }
    }
}
