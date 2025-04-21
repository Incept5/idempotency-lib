package org.incept5.idempotency.tasks

import org.incept5.idempotency.repository.IdempotencyRecordRepository
import org.incept5.scheduler.config.FrequencyConfig
import org.incept5.scheduler.config.NamedTaskConfig
import org.incept5.scheduler.config.RetryConfig
import org.incept5.scheduler.config.std.FrequencyConfigData
import org.incept5.scheduler.model.NamedScheduledTask
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*


/**
 * Run every 10 mins and delete expired idempotency records to clean up the database
 */
class DeleteExpiredIdempotencyRecordsTask(
    private val repo: IdempotencyRecordRepository,
    private val recurs : Duration = Duration.ofMinutes(10)
) : NamedScheduledTask, NamedTaskConfig {

    private val logger = LoggerFactory.getLogger(DeleteExpiredIdempotencyRecordsTask::class.java)
    override fun getName(): String {
        return "DeleteExpiredIdempotencyRecordsTask"
    }

    override fun run() {
        val deleted = repo.deleteExpiredRecords()
        logger.debug ("Deleted $deleted expired idempotency records")
    }

    override fun frequency(): Optional<FrequencyConfig> {
        return Optional.of(FrequencyConfigData(recurs = recurs))
    }

    override fun onFailure(): Optional<RetryConfig> {
        return Optional.empty()
    }

    override fun onIncomplete(): Optional<RetryConfig> {
        return Optional.empty()
    }
}
