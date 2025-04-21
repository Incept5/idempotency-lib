package org.incept5.idempotency.repository

import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus
import java.sql.Timestamp
import javax.sql.DataSource

class SqlIdempotencyRecordRepository(private val dataSource: DataSource, private val schema: String = "") :
    IdempotencyRecordRepository {

    private val tableName = if (schema.isEmpty()) "idempotency_records" else "$schema.idempotency_records"

    override fun createRecord(record: IdempotencyRecord) {
        dataSource.connection.use { conn ->
            val sql = "INSERT INTO $tableName (key, context, request_hash, status, created_at, response, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, record.key)
                stmt.setString(2, record.context)
                stmt.setString(3, record.requestHash)
                stmt.setString(4, record.status.name)
                stmt.setTimestamp(5, Timestamp.from(record.createdAt))
                stmt.setString(6, record.response)
                stmt.setTimestamp(7, record.expiresAt?.let { Timestamp.from(it) })
                stmt.executeUpdate()
            }
        }
    }

    override fun findRecordByKeyAndContext(key: String, context: String): IdempotencyRecord? {
        dataSource.connection.use { conn ->
            val sql = "SELECT * FROM $tableName WHERE key = ? AND context = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, context)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return IdempotencyRecord(
                        key = rs.getString("key"),
                        context = rs.getString("context"),
                        requestHash = rs.getString("request_hash"),
                        status = IdempotencyStatus.valueOf(rs.getString("status")),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        response = rs.getString("response"),
                        expiresAt = rs.getTimestamp("expires_at")?.toInstant()
                    )
                }
            }
        }
        return null
    }

    override fun updateRecordStatus(key: String, context: String, status: IdempotencyStatus, response: String) {
        dataSource.connection.use { conn ->
            val sql = "UPDATE $tableName SET status = ?, response = ? WHERE key = ? AND context = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, status.name)
                stmt.setString(2, response)
                stmt.setString(3, key)
                stmt.setString(4, context)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Delete records that have expired and are no longer needed.
     */
    override fun deleteExpiredRecords(): Int {
        dataSource.connection.use { conn ->
            val sql = "DELETE FROM $tableName WHERE expires_at < CURRENT_TIMESTAMP"
            conn.prepareStatement(sql).use { stmt ->
                return stmt.executeUpdate()
            }
        }
    }
}
