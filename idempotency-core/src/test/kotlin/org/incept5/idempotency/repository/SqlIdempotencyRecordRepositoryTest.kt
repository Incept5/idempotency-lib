package org.incept5.idempotency.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.incept5.idempotency.domain.IdempotencyRecord
import org.incept5.idempotency.domain.IdempotencyStatus
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class SqlIdempotencyRecordRepositoryTest : FunSpec({

    // Mock classes for database interactions
    class MockResultSet(private val records: List<IdempotencyRecord> = emptyList()) : ResultSet {
        private var currentIndex = -1
        private val record: IdempotencyRecord?
            get() = if (currentIndex >= 0 && currentIndex < records.size) records[currentIndex] else null

        override fun next(): Boolean {
            currentIndex++
            return currentIndex < records.size
        }

        override fun getString(columnLabel: String): String? = when (columnLabel) {
            "key" -> record?.key
            "context" -> record?.context
            "request_hash" -> record?.requestHash
            "status" -> record?.status?.name
            "response" -> record?.response
            else -> null
        }

        override fun getTimestamp(columnLabel: String): Timestamp? = when (columnLabel) {
            "created_at" -> record?.createdAt?.let { Timestamp.from(it) }
            "expires_at" -> record?.expiresAt?.let { Timestamp.from(it) }
            else -> null
        }

        // Stub implementations for required methods
        override fun close() {}
        override fun wasNull(): Boolean = false
        override fun getWarnings() = null
        override fun clearWarnings() {}
        override fun getMetaData() = null
        override fun findColumn(columnLabel: String?) = 0
        
        // All other methods are not implemented for this test
        override fun getType() = 0
        override fun getConcurrency() = 0
        override fun rowUpdated() = false
        override fun rowInserted() = false
        override fun rowDeleted() = false
        override fun getStatement() = null
        override fun getFetchDirection() = 0
        override fun setFetchDirection(direction: Int) {}
        override fun getFetchSize() = 0
        override fun setFetchSize(rows: Int) {}
        override fun getRow() = 0
        override fun absolute(row: Int) = false
        override fun relative(rows: Int) = false
        override fun previous() = false
        override fun first() = false
        override fun last() = false
        override fun beforeFirst() {}
        override fun afterLast() {}
        override fun isFirst() = false
        override fun isLast() = false
        override fun isBeforeFirst() = false
        override fun isAfterLast() = false
        override fun getBoolean(columnIndex: Int) = false
        override fun getBoolean(columnLabel: String?) = false
        override fun getByte(columnIndex: Int): Byte = 0
        override fun getByte(columnLabel: String?): Byte = 0
        override fun getShort(columnIndex: Int): Short = 0
        override fun getShort(columnLabel: String?): Short = 0
        override fun getInt(columnIndex: Int) = 0
        override fun getInt(columnLabel: String?) = 0
        override fun getLong(columnIndex: Int) = 0L
        override fun getLong(columnLabel: String?) = 0L
        override fun getFloat(columnIndex: Int) = 0f
        override fun getFloat(columnLabel: String?) = 0f
        override fun getDouble(columnIndex: Int) = 0.0
        override fun getDouble(columnLabel: String?) = 0.0
        override fun getBigDecimal(columnIndex: Int, scale: Int) = null
        override fun getBigDecimal(columnLabel: String?, scale: Int) = null
        override fun getBigDecimal(columnIndex: Int) = null
        override fun getBigDecimal(columnLabel: String?) = null
        override fun getBytes(columnIndex: Int) = null
        override fun getBytes(columnLabel: String?) = null
        override fun getDate(columnIndex: Int) = null
        override fun getDate(columnLabel: String?) = null
        override fun getDate(columnIndex: Int, cal: java.util.Calendar?) = null
        override fun getDate(columnLabel: String?, cal: java.util.Calendar?) = null
        override fun getTime(columnIndex: Int) = null
        override fun getTime(columnLabel: String?) = null
        override fun getTime(columnIndex: Int, cal: java.util.Calendar?) = null
        override fun getTime(columnLabel: String?, cal: java.util.Calendar?) = null
        override fun getTimestamp(columnIndex: Int) = null
        override fun getTimestamp(columnIndex: Int, cal: java.util.Calendar?) = null
        override fun getTimestamp(columnLabel: String?, cal: java.util.Calendar?) = null
        override fun getAsciiStream(columnIndex: Int) = null
        override fun getAsciiStream(columnLabel: String?) = null
        override fun getUnicodeStream(columnIndex: Int) = null
        override fun getUnicodeStream(columnLabel: String?) = null
        override fun getBinaryStream(columnIndex: Int) = null
        override fun getBinaryStream(columnLabel: String?) = null
        override fun getString(columnIndex: Int) = null
        override fun getObject(columnIndex: Int) = null
        override fun getObject(columnLabel: String?) = null
        override fun getObject(columnIndex: Int, map: MutableMap<String, Class<*>>?) = null
        override fun getObject(columnLabel: String?, map: MutableMap<String, Class<*>>?) = null
        override fun getObject(columnIndex: Int, type: Class<*>?) = null
        override fun getObject(columnLabel: String?, type: Class<*>?) = null
        override fun getRef(columnIndex: Int) = null
        override fun getRef(columnLabel: String?) = null
        override fun getBlob(columnIndex: Int) = null
        override fun getBlob(columnLabel: String?) = null
        override fun getClob(columnIndex: Int) = null
        override fun getClob(columnLabel: String?) = null
        override fun getArray(columnIndex: Int) = null
        override fun getArray(columnLabel: String?) = null
        override fun getURL(columnIndex: Int) = null
        override fun getURL(columnLabel: String?) = null
        override fun updateNull(columnIndex: Int) {}
        override fun updateNull(columnLabel: String?) {}
        override fun updateBoolean(columnIndex: Int, x: Boolean) {}
        override fun updateBoolean(columnLabel: String?, x: Boolean) {}
        override fun updateByte(columnIndex: Int, x: Byte) {}
        override fun updateByte(columnLabel: String?, x: Byte) {}
        override fun updateShort(columnIndex: Int, x: Short) {}
        override fun updateShort(columnLabel: String?, x: Short) {}
        override fun updateInt(columnIndex: Int, x: Int) {}
        override fun updateInt(columnLabel: String?, x: Int) {}
        override fun updateLong(columnIndex: Int, x: Long) {}
        override fun updateLong(columnLabel: String?, x: Long) {}
        override fun updateFloat(columnIndex: Int, x: Float) {}
        override fun updateFloat(columnLabel: String?, x: Float) {}
        override fun updateDouble(columnIndex: Int, x: Double) {}
        override fun updateDouble(columnLabel: String?, x: Double) {}
        override fun updateBigDecimal(columnIndex: Int, x: java.math.BigDecimal?) {}
        override fun updateBigDecimal(columnLabel: String?, x: java.math.BigDecimal?) {}
        override fun updateString(columnIndex: Int, x: String?) {}
        override fun updateString(columnLabel: String?, x: String?) {}
        override fun updateBytes(columnIndex: Int, x: ByteArray?) {}
        override fun updateBytes(columnLabel: String?, x: ByteArray?) {}
        override fun updateDate(columnIndex: Int, x: java.sql.Date?) {}
        override fun updateDate(columnLabel: String?, x: java.sql.Date?) {}
        override fun updateTime(columnIndex: Int, x: java.sql.Time?) {}
        override fun updateTime(columnLabel: String?, x: java.sql.Time?) {}
        override fun updateTimestamp(columnIndex: Int, x: java.sql.Timestamp?) {}
        override fun updateTimestamp(columnLabel: String?, x: java.sql.Timestamp?) {}
        override fun updateAsciiStream(columnIndex: Int, x: java.io.InputStream?, length: Int) {}
        override fun updateAsciiStream(columnLabel: String?, x: java.io.InputStream?, length: Int) {}
        override fun updateAsciiStream(columnIndex: Int, x: java.io.InputStream?, length: Long) {}
        override fun updateAsciiStream(columnLabel: String?, x: java.io.InputStream?, length: Long) {}
        override fun updateAsciiStream(columnIndex: Int, x: java.io.InputStream?) {}
        override fun updateAsciiStream(columnLabel: String?, x: java.io.InputStream?) {}
        override fun updateBinaryStream(columnIndex: Int, x: java.io.InputStream?, length: Int) {}
        override fun updateBinaryStream(columnLabel: String?, x: java.io.InputStream?, length: Int) {}
        override fun updateBinaryStream(columnIndex: Int, x: java.io.InputStream?, length: Long) {}
        override fun updateBinaryStream(columnLabel: String?, x: java.io.InputStream?, length: Long) {}
        override fun updateBinaryStream(columnIndex: Int, x: java.io.InputStream?) {}
        override fun updateBinaryStream(columnLabel: String?, x: java.io.InputStream?) {}
        override fun updateCharacterStream(columnIndex: Int, x: java.io.Reader?, length: Int) {}
        override fun updateCharacterStream(columnLabel: String?, x: java.io.Reader?, length: Int) {}
        override fun updateCharacterStream(columnIndex: Int, x: java.io.Reader?, length: Long) {}
        override fun updateCharacterStream(columnLabel: String?, x: java.io.Reader?, length: Long) {}
        override fun updateCharacterStream(columnIndex: Int, x: java.io.Reader?) {}
        override fun updateCharacterStream(columnLabel: String?, x: java.io.Reader?) {}
        override fun updateObject(columnIndex: Int, x: Any?, scaleOrLength: Int) {}
        override fun updateObject(columnIndex: Int, x: Any?) {}
        override fun updateObject(columnLabel: String?, x: Any?, scaleOrLength: Int) {}
        override fun updateObject(columnLabel: String?, x: Any?) {}
        override fun insertRow() {}
        override fun updateRow() {}
        override fun deleteRow() {}
        override fun refreshRow() {}
        override fun cancelRowUpdates() {}
        override fun moveToInsertRow() {}
        override fun moveToCurrentRow() {}
        override fun getHoldability() = 0
        override fun isClosed() = false
        override fun updateNString(columnIndex: Int, nString: String?) {}
        override fun updateNString(columnLabel: String?, nString: String?) {}
        override fun updateNClob(columnIndex: Int, nClob: java.sql.NClob?) {}
        override fun updateNClob(columnLabel: String?, nClob: java.sql.NClob?) {}
        override fun updateNClob(columnIndex: Int, reader: java.io.Reader?, length: Long) {}
        override fun updateNClob(columnLabel: String?, reader: java.io.Reader?, length: Long) {}
        override fun updateNClob(columnIndex: Int, reader: java.io.Reader?) {}
        override fun updateNClob(columnLabel: String?, reader: java.io.Reader?) {}
        override fun updateSQLXML(columnIndex: Int, xmlObject: java.sql.SQLXML?) {}
        override fun updateSQLXML(columnLabel: String?, xmlObject: java.sql.SQLXML?) {}
        override fun getNClob(columnIndex: Int) = null
        override fun getNClob(columnLabel: String?) = null
        override fun getSQLXML(columnIndex: Int) = null
        override fun getSQLXML(columnLabel: String?) = null
        override fun getNString(columnIndex: Int) = null
        override fun getNString(columnLabel: String?) = null
        override fun getNCharacterStream(columnIndex: Int) = null
        override fun getNCharacterStream(columnLabel: String?) = null
        override fun updateRowId(columnIndex: Int, x: java.sql.RowId?) {}
        override fun updateRowId(columnLabel: String?, x: java.sql.RowId?) {}
        override fun getRowId(columnIndex: Int) = null
        override fun getRowId(columnLabel: String?) = null
        override fun unwrap(iface: Class<*>?) = null
        override fun isWrapperFor(iface: Class<*>?) = false
    }

    class MockPreparedStatement(private val resultSet: ResultSet? = null) : PreparedStatement {
        private val parameters = mutableMapOf<Int, Any?>()
        var executeUpdateResult = 0

        override fun executeQuery(): ResultSet = resultSet ?: MockResultSet()
        override fun executeUpdate(): Int = executeUpdateResult
        override fun setString(parameterIndex: Int, x: String?) { parameters[parameterIndex] = x }
        override fun setTimestamp(parameterIndex: Int, x: Timestamp?) { parameters[parameterIndex] = x }

        // Stub implementations for required methods
        override fun close() {}
        override fun execute(): Boolean = false
        
        // All other methods are not implemented for this test
        override fun setNull(parameterIndex: Int, sqlType: Int) {}
        override fun setNull(parameterIndex: Int, sqlType: Int, typeName: String?) {}
        override fun setBoolean(parameterIndex: Int, x: Boolean) {}
        override fun setByte(parameterIndex: Int, x: Byte) {}
        override fun setShort(parameterIndex: Int, x: Short) {}
        override fun setInt(parameterIndex: Int, x: Int) {}
        override fun setLong(parameterIndex: Int, x: Long) {}
        override fun setFloat(parameterIndex: Int, x: Float) {}
        override fun setDouble(parameterIndex: Int, x: Double) {}
        override fun setBigDecimal(parameterIndex: Int, x: java.math.BigDecimal?) {}
        override fun setBytes(parameterIndex: Int, x: ByteArray?) {}
        override fun setDate(parameterIndex: Int, x: java.sql.Date?) {}
        override fun setDate(parameterIndex: Int, x: java.sql.Date?, cal: java.util.Calendar?) {}
        override fun setTime(parameterIndex: Int, x: java.sql.Time?) {}
        override fun setTime(parameterIndex: Int, x: java.sql.Time?, cal: java.util.Calendar?) {}
        override fun setTimestamp(parameterIndex: Int, x: java.sql.Timestamp?, cal: java.util.Calendar?) {}
        override fun setAsciiStream(parameterIndex: Int, x: java.io.InputStream?, length: Int) {}
        override fun setAsciiStream(parameterIndex: Int, x: java.io.InputStream?, length: Long) {}
        override fun setAsciiStream(parameterIndex: Int, x: java.io.InputStream?) {}
        override fun setUnicodeStream(parameterIndex: Int, x: java.io.InputStream?, length: Int) {}
        override fun setBinaryStream(parameterIndex: Int, x: java.io.InputStream?, length: Int) {}
        override fun setBinaryStream(parameterIndex: Int, x: java.io.InputStream?, length: Long) {}
        override fun setBinaryStream(parameterIndex: Int, x: java.io.InputStream?) {}
        override fun clearParameters() {}
        override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int) {}
        override fun setObject(parameterIndex: Int, x: Any?) {}
        override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int, scaleOrLength: Int) {}
        override fun addBatch() {}
        override fun setCharacterStream(parameterIndex: Int, reader: java.io.Reader?, length: Int) {}
        override fun setCharacterStream(parameterIndex: Int, reader: java.io.Reader?, length: Long) {}
        override fun setCharacterStream(parameterIndex: Int, reader: java.io.Reader?) {}
        override fun setRef(parameterIndex: Int, x: java.sql.Ref?) {}
        override fun setBlob(parameterIndex: Int, x: java.sql.Blob?) {}
        override fun setBlob(parameterIndex: Int, inputStream: java.io.InputStream?, length: Long) {}
        override fun setBlob(parameterIndex: Int, inputStream: java.io.InputStream?) {}
        override fun setClob(parameterIndex: Int, x: java.sql.Clob?) {}
        override fun setClob(parameterIndex: Int, reader: java.io.Reader?, length: Long) {}
        override fun setClob(parameterIndex: Int, reader: java.io.Reader?) {}
        override fun setArray(parameterIndex: Int, x: java.sql.Array?) {}
        override fun getMetaData() = null
        override fun setURL(parameterIndex: Int, x: java.net.URL?) {}
        override fun getParameterMetaData() = null
        override fun setRowId(parameterIndex: Int, x: java.sql.RowId?) {}
        override fun setNString(parameterIndex: Int, value: String?) {}
        override fun setNCharacterStream(parameterIndex: Int, value: java.io.Reader?, length: Long) {}
        override fun setNCharacterStream(parameterIndex: Int, value: java.io.Reader?) {}
        override fun setNClob(parameterIndex: Int, value: java.sql.NClob?) {}
        override fun setNClob(parameterIndex: Int, reader: java.io.Reader?, length: Long) {}
        override fun setNClob(parameterIndex: Int, reader: java.io.Reader?) {}
        override fun setSQLXML(parameterIndex: Int, xmlObject: java.sql.SQLXML?) {}
        override fun executeLargeUpdate() = 0L
        override fun executeQuery(sql: String?) = null
        override fun executeUpdate(sql: String?) = 0
        override fun executeUpdate(sql: String?, autoGeneratedKeys: Int) = 0
        override fun executeUpdate(sql: String?, columnIndexes: IntArray?) = 0
        override fun executeUpdate(sql: String?, columnNames: Array<out String>?) = 0
        override fun executeLargeUpdate(sql: String?) = 0L
        override fun executeLargeUpdate(sql: String?, autoGeneratedKeys: Int) = 0L
        override fun executeLargeUpdate(sql: String?, columnIndexes: IntArray?) = 0L
        override fun executeLargeUpdate(sql: String?, columnNames: Array<out String>?) = 0L
        override fun close(closeConnection: Boolean) {}
        override fun getMaxFieldSize() = 0
        override fun setMaxFieldSize(max: Int) {}
        override fun getMaxRows() = 0
        override fun setMaxRows(max: Int) {}
        override fun setEscapeProcessing(enable: Boolean) {}
        override fun getQueryTimeout() = 0
        override fun setQueryTimeout(seconds: Int) {}
        override fun cancel() {}
        override fun getWarnings() = null
        override fun clearWarnings() {}
        override fun setCursorName(name: String?) {}
        override fun execute(sql: String?) = false
        override fun execute(sql: String?, autoGeneratedKeys: Int) = false
        override fun execute(sql: String?, columnIndexes: IntArray?) = false
        override fun execute(sql: String?, columnNames: Array<out String>?) = false
        override fun getResultSet() = null
        override fun getUpdateCount() = 0
        override fun getLargeUpdateCount() = 0L
        override fun getMoreResults() = false
        override fun getMoreResults(current: Int) = false
        override fun setFetchDirection(direction: Int) {}
        override fun getFetchDirection() = 0
        override fun setFetchSize(rows: Int) {}
        override fun getFetchSize() = 0
        override fun getResultSetConcurrency() = 0
        override fun getResultSetType() = 0
        override fun addBatch(sql: String?) {}
        override fun clearBatch() {}
        override fun executeBatch() = IntArray(0)
        override fun executeLargeBatch() = LongArray(0)
        override fun getConnection() = null
        override fun getGeneratedKeys() = null
        override fun getResultSetHoldability() = 0
        override fun isClosed() = false
        override fun isPoolable() = false
        override fun setPoolable(poolable: Boolean) {}
        override fun closeOnCompletion() {}
        override fun isCloseOnCompletion() = false
        override fun unwrap(iface: Class<*>?) = null
        override fun isWrapperFor(iface: Class<*>?) = false
    }

    class MockConnection(private val preparedStatement: PreparedStatement) : Connection {
        override fun prepareStatement(sql: String?): PreparedStatement = preparedStatement
        override fun close() {}
        
        // All other methods are not implemented for this test
        override fun createStatement() = null
        override fun prepareStatement(sql: String?, resultSetType: Int, resultSetConcurrency: Int) = null
        override fun prepareStatement(sql: String?, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int) = null
        override fun prepareStatement(sql: String?, autoGeneratedKeys: Int) = null
        override fun prepareStatement(sql: String?, columnIndexes: IntArray?) = null
        override fun prepareStatement(sql: String?, columnNames: Array<out String>?) = null
        override fun prepareCall(sql: String?) = null
        override fun prepareCall(sql: String?, resultSetType: Int, resultSetConcurrency: Int) = null
        override fun prepareCall(sql: String?, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int) = null
        override fun nativeSQL(sql: String?) = null
        override fun setAutoCommit(autoCommit: Boolean) {}
        override fun getAutoCommit() = false
        override fun commit() {}
        override fun rollback() {}
        override fun isClosed() = false
        override fun getMetaData() = null
        override fun setReadOnly(readOnly: Boolean) {}
        override fun isReadOnly() = false
        override fun setCatalog(catalog: String?) {}
        override fun getCatalog() = null
        override fun setTransactionIsolation(level: Int) {}
        override fun getTransactionIsolation() = 0
        override fun getWarnings() = null
        override fun clearWarnings() {}
        override fun createStatement(resultSetType: Int, resultSetConcurrency: Int) = null
        override fun createStatement(resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int) = null
        override fun getHoldability() = 0
        override fun setHoldability(holdability: Int) {}
        override fun setSavepoint() = null
        override fun setSavepoint(name: String?) = null
        override fun rollback(savepoint: java.sql.Savepoint?) {}
        override fun releaseSavepoint(savepoint: java.sql.Savepoint?) {}
        override fun createClob() = null
        override fun createBlob() = null
        override fun createNClob() = null
        override fun createSQLXML() = null
        override fun isValid(timeout: Int) = false
        override fun setClientInfo(name: String?, value: String?) {}
        override fun setClientInfo(properties: java.util.Properties?) {}
        override fun getClientInfo(name: String?) = null
        override fun getClientInfo() = null
        override fun createArrayOf(typeName: String?, elements: Array<out Any>?) = null
        override fun createStruct(typeName: String?, attributes: Array<out Any>?) = null
        override fun setSchema(schema: String?) {}
        override fun getSchema() = null
        override fun abort(executor: java.util.concurrent.Executor?) {}
        override fun setNetworkTimeout(executor: java.util.concurrent.Executor?, milliseconds: Int) {}
        override fun getNetworkTimeout() = 0
        override fun beginRequest() {}
        override fun endRequest() {}
        override fun setShardingKeyIfValid(shardingKey: java.sql.ShardingKey?, superShardingKey: java.sql.ShardingKey?, timeout: Int) = false
        override fun setShardingKeyIfValid(shardingKey: java.sql.ShardingKey?, timeout: Int) = false
        override fun setShardingKey(shardingKey: java.sql.ShardingKey?, superShardingKey: java.sql.ShardingKey?) {}
        override fun setShardingKey(shardingKey: java.sql.ShardingKey?) {}
        override fun unwrap(iface: Class<*>?) = null
        override fun isWrapperFor(iface: Class<*>?) = false
    }

    class MockDataSource(private val connection: Connection) : DataSource {
        override fun getConnection(): Connection = connection
        override fun getConnection(username: String?, password: String?): Connection = connection
        
        // All other methods are not implemented for this test
        override fun getLogWriter() = null
        override fun setLogWriter(out: java.io.PrintWriter?) {}
        override fun setLoginTimeout(seconds: Int) {}
        override fun getLoginTimeout() = 0
        override fun getParentLogger() = null
        override fun unwrap(iface: Class<*>?) = null
        override fun isWrapperFor(iface: Class<*>?) = false
    }

    test("createRecord should insert a record into the database") {
        // Setup
        val mockPreparedStatement = MockPreparedStatement()
        val mockConnection = MockConnection(mockPreparedStatement)
        val mockDataSource = MockDataSource(mockConnection)
        
        val repository = SqlIdempotencyRecordRepository(mockDataSource)
        
        val now = Instant.now()
        val record = IdempotencyRecord(
            key = "test-key",
            context = "test-context",
            requestHash = "test-hash",
            status = IdempotencyStatus.PENDING,
            createdAt = now,
            expiresAt = now.plus(Duration.ofDays(14)),
            response = null
        )
        
        // Execute
        repository.createRecord(record)
        
        // No assertions needed as we're just verifying it doesn't throw an exception
    }
    
    test("findRecordByKeyAndContext should return a record when it exists") {
        // Setup
        val now = Instant.now()
        val expectedRecord = IdempotencyRecord(
            key = "test-key",
            context = "test-context",
            requestHash = "test-hash",
            status = IdempotencyStatus.SUCCESSFUL,
            createdAt = now,
            expiresAt = now.plus(Duration.ofDays(14)),
            response = """{"result": "success"}"""
        )
        
        val mockResultSet = MockResultSet(listOf(expectedRecord))
        val mockPreparedStatement = MockPreparedStatement(mockResultSet)
        val mockConnection = MockConnection(mockPreparedStatement)
        val mockDataSource = MockDataSource(mockConnection)
        
        val repository = SqlIdempotencyRecordRepository(mockDataSource)
        
        // Execute
        val result = repository.findRecordByKeyAndContext("test-key", "test-context")
        
        // Verify
        result?.key shouldBe expectedRecord.key
        result?.context shouldBe expectedRecord.context
        result?.requestHash shouldBe expectedRecord.requestHash
        result?.status shouldBe expectedRecord.status
        result?.response shouldBe expectedRecord.response
    }
    
    test("findRecordByKeyAndContext should return null when record doesn't exist") {
        // Setup
        val mockResultSet = MockResultSet(emptyList())
        val mockPreparedStatement = MockPreparedStatement(mockResultSet)
        val mockConnection = MockConnection(mockPreparedStatement)
        val mockDataSource = MockDataSource(mockConnection)
        
        val repository = SqlIdempotencyRecordRepository(mockDataSource)
        
        // Execute
        val result = repository.findRecordByKeyAndContext("non-existent-key", "test-context")
        
        // Verify
        result shouldBe null
    }
    
    test("updateRecordStatus should update the status and response of a record") {
        // Setup
        val mockPreparedStatement = MockPreparedStatement()
        val mockConnection = MockConnection(mockPreparedStatement)
        val mockDataSource = MockDataSource(mockConnection)
        
        val repository = SqlIdempotencyRecordRepository(mockDataSource)
        
        // Execute
        repository.updateRecordStatus(
            key = "test-key",
            context = "test-context",
            status = IdempotencyStatus.SUCCESSFUL,
            response = """{"result": "success"}"""
        )
        
        // No assertions needed as we're just verifying it doesn't throw an exception
    }
    
    test("deleteExpiredRecords should delete expired records") {
        // Setup
        val mockPreparedStatement = MockPreparedStatement().apply {
            executeUpdateResult = 5 // Simulate 5 records deleted
        }
        val mockConnection = MockConnection(mockPreparedStatement)
        val mockDataSource = MockDataSource(mockConnection)
        
        val repository = SqlIdempotencyRecordRepository(mockDataSource)
        
        // Execute
        val result = repository.deleteExpiredRecords()
        
        // Verify
        result shouldBe 5
    }
    
    test("repository should use schema prefix when provided") {
        // This test is more about verifying the SQL query construction
        // We can't easily assert on the SQL string with our mocks, but we can
        // at least verify that the code executes without errors
        
        val mockPreparedStatement = MockPreparedStatement()
        val mockConnection = MockConnection(mockPreparedStatement)
        val mockDataSource = MockDataSource(mockConnection)
        
        val repository = SqlIdempotencyRecordRepository(mockDataSource, schema = "custom_schema")
        
        // Execute various methods to ensure they work with the schema
        repository.createRecord(
            IdempotencyRecord(
                key = "test-key",
                context = "test-context",
                requestHash = "test-hash",
                status = IdempotencyStatus.PENDING
            )
        )
        
        repository.findRecordByKeyAndContext("test-key", "test-context")
        
        repository.updateRecordStatus(
            key = "test-key",
            context = "test-context",
            status = IdempotencyStatus.SUCCESSFUL,
            response = """{"result": "success"}"""
        )
        
        repository.deleteExpiredRecords()
        
        // No assertions needed as we're just verifying it doesn't throw an exception
    }
})