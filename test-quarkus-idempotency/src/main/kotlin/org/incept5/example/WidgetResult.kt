package org.incept5.example

import java.util.UUID

data class WidgetResult(val name: String, val id: UUID = UUID.randomUUID()) {
    /**
     * Fails JSON serialisation for names starting "unserialisable", to force the SUCCESSFUL status write to fail
     * after the operation's work is done
     */
    @Suppress("unused")
    val check: String
        get() = if (name.startsWith("unserialisable")) throw IllegalStateException("cannot serialise $name") else "ok"
}
