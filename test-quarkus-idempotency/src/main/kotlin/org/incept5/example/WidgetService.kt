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
import jakarta.transaction.Synchronization
import jakarta.transaction.TransactionSynchronizationRegistry
import javax.sql.DataSource

/**
 * A use case with its own @Transactional work (a widget row) whose behaviour the tests script by name:
 * - "reject..." throws a CoreException(VALIDATION) after writing, i.e. a deterministic rejection
 * - [transientFailures] makes the next N calls for a name throw an IllegalStateException after writing
 * - [gates] holds a call (after it has been counted in [entered]) until the test releases it
 * - [commitFailures] makes the next N calls for a name fail at commit, in beforeCompletion, which is where an ORM such
 *   as Hibernate runs its deferred SQL: the call returns normally and the error surfaces when the transaction commits
 */
@ApplicationScoped
class WidgetService(
    private val dataSource: DataSource,
    private val synchronizations: TransactionSynchronizationRegistry,
) {

    companion object {
        val runs = ConcurrentHashMap<String, AtomicInteger>()
        val transientFailures = ConcurrentHashMap<String, AtomicInteger>()
        val entered = ConcurrentHashMap<String, CountDownLatch>()
        val gates = ConcurrentHashMap<String, CountDownLatch>()
        val commitFailures = ConcurrentHashMap<String, AtomicInteger>()

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

        if ((commitFailures[command.name]?.getAndDecrement() ?: 0) > 0) {
            synchronizations.registerInterposedSynchronization(object : Synchronization {
                override fun beforeCompletion() = throw IllegalStateException("simulated flush failure at commit for ${command.name}")
                override fun afterCompletion(status: Int) {}
            })
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
