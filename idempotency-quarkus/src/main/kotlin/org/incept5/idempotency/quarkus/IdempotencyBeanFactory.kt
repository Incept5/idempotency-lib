package org.incept5.idempotency.quarkus

import org.incept5.idempotency.repository.IdempotencyRecordRepository
import org.incept5.idempotency.repository.SqlIdempotencyRecordRepository
import org.incept5.idempotency.service.IdempotencyChecker
import org.incept5.idempotency.service.IdempotencyRecordCreator
import org.incept5.idempotency.service.IdempotencyRecordUpdater
import org.incept5.idempotency.tasks.DeleteExpiredIdempotencyRecordsTask
import jakarta.enterprise.context.RequestScoped
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import javax.sql.DataSource

/**
 * Add the beans we need for Idempotency checking to the Quarkus CDI container
 */
class IdempotencyBeanFactory {

    @Singleton
    fun idempotencyRecordRepository(dataSource: DataSource,  @ConfigProperty(name = "quarkus.flyway.default-schema") schema: String = ""): IdempotencyRecordRepository {
        return SqlIdempotencyRecordRepository(dataSource, schema)
    }

    @Singleton
    fun idempotencyRecordCreator(
        repository: IdempotencyRecordRepository,
        @ConfigProperty(name = "idempotency.expire-records-after-duration", defaultValue = "P14D") expireAfter: String = "P14D"
    ): IdempotencyRecordCreator {
        return IdempotencyRecordCreator(repository, Duration.parse(expireAfter))
    }

    @Singleton
    fun idempotencyRecordUpdater(repository: IdempotencyRecordRepository): IdempotencyRecordUpdater {
        return IdempotencyRecordUpdater(repository)
    }

    @RequestScoped
    fun idempotencyChecker(creator: IdempotencyRecordCreator, updater: IdempotencyRecordUpdater): IdempotencyChecker {
        return IdempotencyChecker(creator, updater)
    }

    @Singleton
    fun deleteExpiredIdempotencyRecordsTask(
        repository: IdempotencyRecordRepository,
        @ConfigProperty(name = "idempotency.delete-expired-records-task-recurs", defaultValue = "PT10M") recurs: String = "PT10M"
    ): DeleteExpiredIdempotencyRecordsTask {
        return DeleteExpiredIdempotencyRecordsTask(repository, Duration.parse(recurs))
    }
}
