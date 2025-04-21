package org.incept5.idempotency.service

import org.incept5.error.CoreException
import org.incept5.error.Error
import org.incept5.error.ErrorCategory
import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus
import org.incept5.idempotency.exp.IdempotencyConflictException
import org.incept5.json.Json
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
 * val result = idempotencyChecker.ensureIdempotency<YourResponseType>("my-context", requestHash, {
 *     // your service call here
 *     myServiceLayer.doSomething(request)
 * })
 *
 * the context parameter is used to namespace the idempotency records and ensure that one client
 * doesn't tangle up with another clients records. So it should be set to something like the organization id,
 * merchant id, user id, client id etc
 *
 * If the requestHash does not match but the idempotent key does then an error is thrown
 *
 */
class IdempotencyChecker(
    val recordCreator: IdempotencyRecordCreator,
    val recordUpdater: IdempotencyRecordUpdater
) {

    // this will get set by the filter from Idempotency-Key header
    var idempotencyKey: String? = null

    /**
     * This method is bigger than it should be but is hampered by the inline nature of it
     * in order to support the reified type parameter
     */
    inline fun <reified T> ensureIdempotency(context: String, requestHash: String, operation: () -> Any) : T {
        if (idempotencyKey == null) {
            throw CoreException(
                category = ErrorCategory.VALIDATION,
                errors = listOf(Error("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header")),
                message = "Idempotency-Key header is required for this resource but was missing from the request"
            )
        }

        val resultIsString = T::class.java == String::class.java
        val record : IdempotencyRecord?
        try{
            // this call will throw an exception if the request is already being processed
            record = recordCreator.ensureRecord(idempotencyKey!!, context, requestHash)
        }
        catch (e: SQLException) {
            if ( e.message != null && (e.message!!.contains("unique constraint") && e.message!!.contains("idempotency_records_pkey")) ) {
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

        // return the previous successful response
        if ( record.status == IdempotencyStatus.SUCCESSFUL ) {
            // we have already processed this request
            if ( resultIsString){
                // response is a string and required type is a string so just return it
                return record.response as T
            }
            // response is a json string and required type is a class so marshal it
            return Json.fromJson(record.response!!, T::class.java)
        }

        if (record.status == IdempotencyStatus.ERROR) {
            val error = Json.fromJson(record.response!!, ErrorHolder::class.java)
            // we have already processed this request and it failed so return the same error
            throw CoreException(
                category = error.category,
                errors = listOf(error.error),
                message = error.message
            )
        }

        // we are processing this request ourselves for the first time so we need to capture
        // the result and store it in the db and update the status
        try {
            val result = operation()
            val response = if (resultIsString) {
                result as String
            } else {
                Json.toJson(result)
            }
            recordUpdater.updateRecordStatus(idempotencyKey!!, context, IdempotencyStatus.SUCCESSFUL, response)
            return result as T
        }
        catch (e: CoreException) {
            // operation threw a CoreException which we need to store in the db for future requests
            recordUpdater.updateRecordStatus(idempotencyKey!!, context, IdempotencyStatus.ERROR, Json.toJson(ErrorHolder.fromCoreException(e)))
            throw e
        }
        catch (e: Exception) {
            // operation threw an unexpected exception which we need to store in the db for future requests
            recordUpdater.updateRecordStatus(idempotencyKey!!, context, IdempotencyStatus.ERROR, Json.toJson(ErrorHolder.fromThrowable(e)))
            throw e
        }
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
