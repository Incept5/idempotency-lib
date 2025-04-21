# Idempotency Lib

A lightweight library for implementing idempotent operations in Quarkus applications.

## Overview

This library provides a set of classes that allow services to ensure idempotency of their operations. Idempotency ensures that an operation is only executed once, even if the same request is received multiple times.

For example, a payment service might want to ensure that a payment is only processed once, even if the request is received multiple times. This library uses the `Idempotency-Key` HTTP header to identify requests and prevent duplicate processing.

For more information on the idempotency key standard, see the [IETF draft specification](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/).

## Installation

### Gradle (Kotlin DSL)

#### Using Version Catalog (recommended)

In your `libs.versions.toml` file:

```toml
[versions]
incept5-idempotency = "1.0.X" # Replace X with the latest version

[libraries]
incept5-idempotency-core = { module = "com.github.incept5.idempotency-lib:idempotency-core", version.ref = "incept5-idempotency" }
incept5-idempotency-quarkus = { module = "com.github.incept5.idempotency-lib:idempotency-quarkus", version.ref = "incept5-idempotency" }
```

In your `build.gradle.kts` file:

```kotlin
dependencies {
    implementation(libs.incept5.idempotency.quarkus)
}
```

#### Without Version Catalog

In your `build.gradle.kts` file:

```kotlin
dependencies {
    implementation("com.github.incept5.idempotency-lib:idempotency-quarkus:1.0.X") // Replace X with the latest version
}
```

### Maven

Add the following to your `pom.xml`:

```xml
<dependencies>
    <dependency>
        <groupId>com.github.incept5.idempotency-lib</groupId>
        <artifactId>idempotency-quarkus</artifactId>
        <version>1.0.X</version> <!-- Replace X with the latest version -->
    </dependency>
</dependencies>
```

## Usage

### Configuration

#### Required Flyway Scripts

Add idempotency and scheduler as extra Flyway locations in your `application.yaml`:

```yaml
quarkus:
  flyway:
    locations: /db/migration,/incept5/idempotency,/incept5/scheduler
```

This includes both the idempotency and scheduler scripts, which are required for the library to function properly.

### Protecting a Service Method

To ensure a method is only executed once per idempotency key:

```kotlin
@Path("/example")
class ExampleResource(
    private val exampleService: ExampleService,
    private val idempotencyChecker: IdempotencyChecker
) {
    @POST
    fun createExample(request: ExampleRequest): Response {
        val command = ExampleCommand(request.orgId!!, request.name!!)
        val requestHash = IdempotencyHasher.hashRequest(command)
        
        // The result will be stored in the idempotency_records table
        val response = idempotencyChecker.ensureIdempotency<ExampleResponse>(command.orgId, requestHash) {
            val example = exampleService.createExample(command)
            ExampleResponse(example.id, example.name)
        }
        
        return Response.ok(response)
            .contentLocation(URI.create("/example/${response.id}"))
            .build()
    }
}
```

### Context Namespacing

You must provide a "context" string (like `command.orgId` in the example above) to namespace idempotency checks. Using identifiers like username, merchant ID, or client ID ensures that different clients won't interfere with each other, even if they use the same idempotency key.

### Request Hash Validation

The `requestHash` parameter allows the library to verify that subsequent requests with the same idempotency key are semantically identical to the original request:

- If the hash matches, the cached result is returned
- If the hash differs, a 400 Bad Request response is returned
- For concurrent requests with the same key, a 409 Conflict is returned for all but the first request

The `IdempotencyHasher` utility helps generate consistent hashes for your request objects. You may need to exclude fields like "timestamp" from the hash calculation if you want to treat slightly different requests as the same.

### Minimizing Stored Data

The idempotency library stores a JSON representation of the operation result in the database. This allows subsequent requests with the same idempotency key to return the cached result without executing the operation again.

This has two important implications:

1. Large result objects will consume significant database storage
2. Sensitive data in result objects will be stored in the database (without encryption by default)

To minimize the data stored, you can return only essential information:

```kotlin
@POST
fun createExample(request: ExampleRequest): Response {
    val command = ExampleCommand(request.orgId!!, request.name!!)
    
    // Only store the ID in the database
    val id = idempotencyChecker.ensureIdempotency<String>(command.orgId, requestHash) {
        val result = exampleService.createExample(command)
        result.id.toString()
    }

    return Response
        .created(URI.create("/example/$id"))
        .build()
}
```

This approach is particularly useful when returning a 201 Created response with a Location header, as you only need to store the ID.

### Record Expiration

By default, idempotency records are kept for 2 weeks. You can customize this in your `application.yaml`:

```yaml
idempotency:
  # Only track idempotency across 3 days
  expire-records-after-duration: P3D
```

The duration uses the ISO-8601 duration format (e.g., P1D for 1 day, PT12H for 12 hours).
