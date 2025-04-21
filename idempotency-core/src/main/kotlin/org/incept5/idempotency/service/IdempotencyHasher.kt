package org.incept5.idempotency.service

import org.incept5.json.Json
import java.security.MessageDigest

/**
 * Helper class to hash the request
 *
 * It's important to note that this might not work for all
 * request types and especially if the request contains a timestamp
 * of some kind that might change between copies of the same semantic request
 *
 */
object IdempotencyHasher {

    /**
     * Helper method to hash the request
     */
    fun hashRequest (request: Any): String {
        return sha256(Json.toJson(request))
    }

    private fun sha256(string: String): String {
        val bytes = string.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("", { str, it -> str + "%02x".format(it) })
    }
}
