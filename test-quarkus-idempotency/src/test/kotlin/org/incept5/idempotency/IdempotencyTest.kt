package org.incept5.idempotency

import org.incept5.example.ExampleCommand
import org.incept5.example.ExampleResponse
import org.incept5.http.client.HttpClient
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import okhttp3.Interceptor
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.CoreMatchers
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


@QuarkusTest
class IdempotencyTest {

    @ConfigProperty(name = "test.url")
    lateinit var testUrl: String

    @Test
    fun `should return 200 when creating a new example`() {
        // Given
        val command = ExampleCommand(
            orgId = "orgId1",
            name = "name1"
        )

        val idempotencyKey = UUID.randomUUID().toString()

        // When
        val response = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(command)
            .`when`()
            .post("/example")

        // Then
        response.then()
            .statusCode(200)
    }

    @Test
    fun `should return 400 when missing Idempotency-Key`() {
        // Given
        val command = ExampleCommand(
            orgId = "orgIdX",
            name = "nameX"
        )

        // When
        val response = RestAssured.given()
            .contentType("application/json")
            .body(command)
            .`when`()
            .post("/example")

        // Then
        response.then()
            .statusCode(400)
            .body(CoreMatchers.containsString("Idempotency-Key header is required for this resource but was missing from the request"))
    }

    @Test
    fun `should handle 2nd non-overlapping request with previous successful result`() {
        // Given
        val command = ExampleCommand(
            orgId = "orgId2",
            name = "name2"
        )

        // use the same idempotency key for both requests
        val idempotencyKey = UUID.randomUUID().toString()

        // First request for the name2 should succeed and store result
        val response = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(command)
            .`when`()
            .post("/example")

        // Then
        response.then()
            .statusCode(200)
            .body(CoreMatchers.containsString("name2"))

        // Second request for the name2 should succeed with previous result and not cause an error
        // The service will throw an error if asked to create the same name twice
        val response2 = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(command)
            .`when`()
            .post("/example")

        // Then
        response2.then()
            .statusCode(200)
            .body(CoreMatchers.containsString("name2"))

    }

    @Test
    fun `should handle 2nd non-overlapping request with previous erroneous result`() {
        // Given
        val command = ExampleCommand(
            orgId = "orgId5",
            name = "error_1"
        )

        // use the same idempotency key for both requests
        val idempotencyKey = UUID.randomUUID().toString()

        // First request for the name2 should succeed and store result
        val response = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(command)
            .`when`()
            .post("/example")

        // Then
        response.then()
            .statusCode(400)
            .body(CoreMatchers.containsString("error_1"))

        // Second request for the name2 should succeed with previous result and not cause an error
        // The service will throw an error if asked to create the same name twice
        val response2 = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(command)
            .`when`()
            .post("/example")

        // Then
        response2.then()
            .statusCode(400)
            .body(CoreMatchers.containsString("error_1"))

    }

    /**
     * This test will use 10 threads to call the same endpoint with the same idempotency key
     * and an http client that will automatically retry on 409 conflict errors
     */
    @Test
    fun `should handle many overlapping requests with the same idempotency key`(){

        val idempotencyKey = UUID.randomUUID().toString()

        val client = HttpClient(baseUri = testUrl, interceptors = listOf(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Idempotency-Key", idempotencyKey).build())
        }))

        val command = ExampleCommand(
            orgId = "orgId3",
            name = "name3"
        )

        val callable: () -> ExampleResponse? = {
            client.postJson<ExampleResponse>(
                path = "/example",
                body = command
            )
        }

        val executor = Executors.newFixedThreadPool(10)

        val futures = (1..10).map {
            executor.submit(callable)
        }

        futures.forEach {
            val response: ExampleResponse? = it.get(5, TimeUnit.SECONDS) as ExampleResponse
            assert(response != null)
            assert(response!!.name == "name3")
        }

    }

    @Test
    fun `should return 200 when creating a new example with string result`() {
        // Given
        val command = ExampleCommand(
            orgId = "orgId7",
            name = "name7"
        )

        val idempotencyKey = UUID.randomUUID().toString()

        // When
        val response = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(command)
            .`when`()
            .post("/example/string-result")

        // Then
        response.then()
            .statusCode(201)

        val response2 = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(command)
            .`when`()
            .post("/example/string-result")

        // Then
        response2.then()
            .statusCode(201)
    }

    @Test
    fun `should return 400 when idempotency key reused with different request`() {
        // Given
        val command = ExampleCommand(
            orgId = "orgId7",
            name = "name8"
        )

        val idempotencyKey = UUID.randomUUID().toString()

        // When
        val response = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(command)
            .`when`()
            .post("/example/string-result")

        // Then
        response.then()
            .statusCode(201)

        val response2 = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(command.copy(name = "name9"))
            .`when`()
            .post("/example/string-result")

        // Then
        response2.then()
            .statusCode(400)
            .body(CoreMatchers.containsString("The Idempotency-Key has previously been used for a different request. Please try again with a new Idempotency-Key header."))
    }

}
