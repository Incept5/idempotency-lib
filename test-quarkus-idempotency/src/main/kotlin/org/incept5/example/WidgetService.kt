package org.incept5.example

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.incept5.error.CoreException
import org.incept5.error.Error
import org.incept5.error.ErrorCategory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * A use case with its own @Transactional work (a widget row) whose behaviour the tests script by name:
 * - "reject..." throws a CoreException(VALIDATION) after writing, i.e. a deterministic rejection
 * - [transientFailures] makes the next N calls for a name throw an IllegalStateException after writing
 * - [gates] holds a call (after it has been counted in [entered]) until the test releases it
 */
@ApplicationScoped
class WidgetService(private val dataSource: DataSource) {

    companion object {
        val runs = ConcurrentHashMap<String, AtomicInteger>()
        val transientFailures = ConcurrentHashMap<String, AtomicInteger>()
        val entered = ConcurrentHashMap<String, CountDownLatch>()
        val gates = ConcurrentHashMap<String, CountDownLatch>()

        fun runsFor(name: String): Int = runs[name]?.get() ?: 0
    }

    @Transactional
    fun create(command: ExampleCommand): WidgetResult {
        runs.computeIfAbsent(command.name) { AtomicInteger() }.incrementAndGet()
        entered[command.name]?.countDown()
        gates[command.name]?.await(10, TimeUnit.SECONDS)

        dataSource.connection.use { conn ->
            conn.prepareStatement("INSERT INTO example.widget (name, org_id) VALUES (?, ?)").use { stmt ->
                stmt.setString(1, command.name)
                stmt.setString(2, command.orgId)
                stmt.executeUpdate()
            }
        }

        if (command.name.startsWith("reject")) {
            throw CoreException(
                category = ErrorCategory.VALIDATION,
                errors = listOf(Error("widget_rejected", "name")),
                message = "widget rejected: ${command.name}"
            )
        }
        if ((transientFailures[command.name]?.getAndDecrement() ?: 0) > 0) {
            throw IllegalStateException("temporary failure creating ${command.name}")
        }
        return WidgetResult(command.name)
    }

    fun countWidgets(name: String): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM example.widget WHERE name = ?").use { stmt ->
                stmt.setString(1, name)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }
}
