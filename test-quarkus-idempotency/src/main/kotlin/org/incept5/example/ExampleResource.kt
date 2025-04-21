package org.incept5.example

import org.incept5.idempotency.service.IdempotencyChecker
import org.incept5.idempotency.service.IdempotencyHasher
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import java.net.URI

@Path("/example")
class ExampleResource(
    private val exampleService: ExampleService,
    private val idempotencyChecker: IdempotencyChecker
) {
    @POST
    fun createExample(request: ExampleRequest): Response {
        val command = ExampleCommand(request.orgId!!, request.name!!)

        val requestHash = IdempotencyHasher.hashRequest(command)

        // result json will be stored in idempotency_records table under "result" column
        val result = idempotencyChecker.ensureIdempotency<ExampleResult>(command.orgId, requestHash) {
            exampleService.createExample(command)
        }

        val response = ExampleResponse(result.id, result.name)
        return Response.ok(response)
            .contentLocation(URI.create("/example/${response.id}"))
            .build()
    }

    @POST
    @Path("/string-result")
    fun createExampleStringResult(request: ExampleRequest): Response {
        val command = ExampleCommand(request.orgId!!, request.name!!)

        val requestHash = IdempotencyHasher.hashRequest(command)

        // result json will be stored in idempotency_records table under "result" column
        val result = idempotencyChecker.ensureIdempotency<String>(command.orgId, requestHash) {
            val result = exampleService.createExample(command)
            result.id.toString()
        }
        return Response.created(URI.create("/example/$result")).build()
    }
}
