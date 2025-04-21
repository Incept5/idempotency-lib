# Idempotency Lib

This library provides a set of classes that allow services to ensure idempotency of their operations.

For example, a payment service might want to ensure that a payment is only processed once, even if the request is received multiple times.
To do that an http request header called `Idempotency-Key` is used to identify the request and the service can then use this library to ensure that the operation is only processed once.

See https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/ for more information on the standard.

## Usage

First you need to include the following library in your project:

Add to toml file:

    [versions]
    incept5-idempotency = "1.0.X"

    [dependencies]
    incept5-idempotency-quarkus = { module = "com.github.incept5.idempotency-lib:idempotency-quarkus", version.ref = "incept5-idempotency" }

And then in your build.gradle.kts file:

    implementation(libs.incept5.idempotency.quarkus)

### Required Flyway Script

Simplest option is to add idempotency and scheduler as an extra flyway location in your application.yaml:

    quarkus:
      flyway:
        locations: /db/migration,/incept5/idempotency,/incept5/scheduler

This is because we also need to include the scheduler flyway scripts as well as the idempotency scripts.

### Protect a service method from being called multiple times

To protect a service method from being called multiple times you can use the `IdempotencyChecker` class like so:

    @Path("/example")
    class ExampleResource(
        private val exampleService: ExampleService,
        private val idempotencyChecker: IdempotencyChecker
    ) {

        @POST
        fun createExample(request: ExampleRequest): Response {
            val command = ExampleCommand(request.orgId!!, request.name!!)

            val requestHash = IdempotencyHasher.hashRequest(command)
    
            // response json will be stored in idempotency_records table under "result" column
            val response = idempotencyChecker.ensureIdempotency<ExampleResponse>(command.orgId, requestHash) {
                val example = exampleService.createExample(command)
                ExampleResponse(example.id, example.name)
            }
            return Response.ok(response)
                .contentLocation(URI.create("/example/${response.id}"))
                .build()
        }
    }

You need to pass in a "context" string in order to namespace the idempotency checks. If we use the username, merchant id, client id
or similar then it means that 2 different clients of our system won't interact with each other even if they happen to choose
the same Idempotency-Key for a particular request.

The `IdempotencyChecker` will ensure that the operation is only processed once and will return the result of the operation.
Overlapping requests will cause a 409 Conflict response to be returned from all requests except the first one.

We also need to provide a requestHash which allows us to check that the request is semantically the same as the previous request.
If it is not then we will return a 400 Bad Request response.

NOTE: we leave it up to the client code to generate the requestHash in case it needs to be generated in a specific way.
But we provide the `IdempotencyHasher` class to help with this for the normal use case. It might be necessary to remove some
items from the hash such as "timestamp" if you need to treat slightly different requests as the same.

### A note about Result class

It should be noted that under the covers the idempotency lib is storing a JSON representation of the result coming back
from the service layer in the database. This is so that subsequent requests with the same Idempotency-Key can be handled
without hitting the service at all and just returning the previous result.

This has 2 consequences:

1) if your result object is large we might be storing a lot of data in the database
2) if your result object contains sensitive data then it will be stored in the database without encryption currently (could extend to use vault if necessary)

If you only need the id of the created result and not the entire result object because you are returning a 201 created with a Location header
then you can amend the code as follows and only store the id in the database:

        @POST
        fun createExample(request: ExampleRequest): Response {
            val command = ExampleCommand(request.orgId!!, request.name!!)
    
            // result json will be stored in idempotency_records table under "result" column
            val id = idempotencyChecker.ensureIdempotency<String>(command.orgId) {
                val result = exampleService.createExample(command)
                result.id.toString()
            }

            return Response
                .created(URI.create("/example/$id"))
                .build()
        }
    }

And this will minimize the amount of data stored in the database.

### Expiration of database records

By default we will track idempotency records for 2 weeks. To change this you can set the following in your application.yaml:

    idempotency:
        # Only track idempotency across 3 days
        expire-records-after-duration: P3D
