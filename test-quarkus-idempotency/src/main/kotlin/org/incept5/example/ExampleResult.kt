package org.incept5.example

import java.util.UUID

data class ExampleResult(val name: String, val id: UUID = UUID.randomUUID())
