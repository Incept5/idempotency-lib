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

### Failures: Cached by Default, Retryable on Request

By default every exception the operation throws is cached as an `ERROR` and replayed for the same key, so a retry
gets the same error without the operation running again. That is right for a deterministic rejection (a 4xx), but
it also caches temporary failures such as a dropped database connection, and the client then needs a new key.

Pass `IdempotencyOptions` with a `retryOn` policy to let temporary failures be retried with the same key:

```kotlin
val response = idempotencyChecker.ensureIdempotency<ExampleResponse>(
    command.orgId,
    requestHash,
    IdempotencyOptions(retryOn = IdempotencyRetry.unlessRejected),
) {
    exampleService.createExample(command)
}
```

When `retryOn` returns `true` for the exception the operation threw, the record is marked `RETRYABLE` instead of
`ERROR` and the exception is rethrown. A failure *after* the operation has returned (serialising its result, or writing
the `SUCCESSFUL` status) is always cached as `ERROR`, because the operation's work is already committed and a retry
would run it twice. Use `successInOperationTransaction` (below) to make those failures roll the work back and become
retryable too. The next request with the same key **and the same request hash** claims the record (one
conditional update, so of two concurrent retries exactly one runs and the other gets a 409) and runs the operation
again.

- `IdempotencyRetry.unlessRejected` retries everything except a `CoreException` in a 4xx category (anything but
  `UNEXPECTED`), including one attached as a suppressed exception or wrapped as a cause. Rejections stay cached.
- `IdempotencyRetry.never` is the default.
- A key freed by a temporary failure stays bound to the request it was first used for. A different payload sent
  with it gets `IDEMPOTENCY_HASH_MISMATCH` (400), the same as after a success or a rejection.
- A retry does not extend the key's life: a `RETRYABLE` record keeps its original `expires_at`.
- Must be called outside a transaction (no `@Transactional` on the resource method), as with
  `successInOperationTransaction` below: inside a caller's transaction the operation's work commits later than its
  status, so a rollback could replay a success that never happened. The checker refuses such a call with an
  `IllegalStateException` before it touches the record.

**Only opt in when a failure leaves no side effect**: the operation's database work rolled back and no external call
was made. Do **not** use `retryOn` for an operation that calls a payment processor or any other external system: a
failure after that call succeeded would run the call again on retry.

### Writing Success in the Operation's Transaction

By default the operation's own `@Transactional` work commits first and the library then writes `SUCCESSFUL` in a
separate transaction. If that write fails (or the process dies in between) the work is committed but the key is not
`SUCCESSFUL`.

Set `successInOperationTransaction = true` to run the operation and the `SUCCESSFUL` write in one transaction that the
checker opens. A `@Transactional` (REQUIRED) use case called from the operation joins it, so the work commits if and
only if the key becomes `SUCCESSFUL`. If anything fails the work rolls back, and the failure
(`ERROR`, or `RETRYABLE` under `retryOn`) is written in its own transaction, so the key is never left `PENDING` by a
rolled-back attempt.

```kotlin
IdempotencyOptions(retryOn = IdempotencyRetry.unlessRejected, successInOperationTransaction = true)
```

Things that change when you opt in:

- The operation's work commits later: at the end of `ensureIdempotency`, after the result has been serialised.
- Use cases that commit in several steps (e.g. persist state, call a payment processor, then record the outcome)
  are merged into one transaction held open across the external call. Leave those on the default.
- Work the operation does in its own `REQUIRES_NEW` transaction, or an external call it makes, is not rolled back.
- Must be called outside a transaction (no `@Transactional` on the resource method), as must any non-default
  options: if the checker joined a caller's transaction that later rolled back, the key would be left `PENDING`. The
  checker refuses such a call with an `IllegalStateException` before it touches the record.
- Requires the `idempotency-quarkus` module (or your own `IdempotencyTransactions` implementation passed to
  `IdempotencyChecker`).

### Known Limit: Crashes Leave PENDING

A process that dies after the record is created and before the operation finishes leaves the record `PENDING`.
Every request with that key then gets a 409 until the record expires (14 days by default). This is unchanged by the
options above. A possible future option is to treat a `PENDING` record older than N minutes as abandoned.

### Do Not Edit the Flyway Script

`incept5/idempotency/R__01_idempotency_record.sql` is a repeatable migration that starts with `DROP TABLE`. Any change
to its checksum wipes the idempotency records of every consuming service on its next deploy, and with them the replay
protection for keys in flight. The build fails if the file changes (`verifyIdempotencyMigrationUnchanged`). The
`RETRYABLE` status needed no schema change: `status` is a `VARCHAR` with no check constraint.

## Release Notes

### Next release (FF-4394): retryable failures and in-transaction success

- New `IdempotencyOptions` overload of `ensureIdempotency` with `retryOn` (default never) and
  `successInOperationTransaction` (default false). Calls without options behave exactly as in 1.0.13.
- New `RETRYABLE` record status, and `IdempotencyRetry.unlessRejected`.
- Any non-default options are refused inside a caller's transaction.
- `IdempotencyRecordRepository` gains `markRetryable` and `claimRetryable`, with default implementations that throw,
  so custom repositories still compile; they are only called when `retryOn` is used.
- The `operation` lambda is now `noinline`: a bare `return` from inside it no longer compiles (in 1.0.13 it left the
  key `PENDING`; use `return@ensureIdempotency`), and it can no longer call `suspend` functions from an enclosing
  `suspend` function.
- `idempotency-quarkus` now depends on `quarkus-narayana-jta` (for `QuarkusTransaction`). Services using a JDBC
  datasource already have it through Agroal.
- A duplicate-key `SQLException` wrapped in another exception is now also reported as a 409 conflict.
- The library's `@Transactional(REQUIRES_NEW)` annotations on `IdempotencyRecordCreator`/`IdempotencyRecordUpdater`
  are not applied by Quarkus, because those beans come from producer methods. The default path keeps 1.0.13's
  actual behaviour (each statement auto-commits, or joins the caller's transaction if it has one). Calls with options
  demarcate their transactions explicitly through `IdempotencyTransactions`.
- **Rolling deploys.** An instance still on 1.0.13 that reads a `RETRYABLE` record fails in
  `IdempotencyStatus.valueOf` and returns a 500 for that key before running the operation, so nothing is cached or
  duplicated. No record is `RETRYABLE` until an instance on the new version has opted an operation in, and the window
  lasts only as long as the rollout. Deploy services normally.
