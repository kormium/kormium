import io.github.kormium.KormiumConfig
import io.github.kormium.SqlExecutor
import io.github.kormium.WriteListeners
import io.github.kormium.SqlParameterSource
import io.github.kormium.StandardDialect
import io.github.kormium.StandardTypeMapper
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TransactionIsolation
import io.github.kormium.database.Database
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.resultset.ResultSet

class DatabaseMock: Database<Nothing>, SuspendDatabase<Nothing> {

    override var config = KormiumConfig()
    override val writeListeners = WriteListeners()
    override val isClosed: Boolean = false
    override val dialect = StandardDialect

    var result: Any? = null
    var internalSql: String = ""
    var internalParams: Map<String, Any?> = emptyMap()

    // The mock records SQL rather than pinning a real connection; both the blocking and the
    // suspend scopes get an executor that writes back into these fields (BEGIN/COMMIT are no-ops).
    override fun <R> usePinned(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: (SqlExecutor) -> R,
    ): R = block(MockExecutor(this))

    override suspend fun <R> useConnection(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R = block(SuspendMockExecutor(this))

    override fun close() = Unit
}

// Records the SQL/params it is handed (and returns the mock's canned [DatabaseMock.result]).
private class MockExecutor(private val mock: DatabaseMock) : SqlExecutor {
    override val dialect = StandardDialect
    override val typeMapper = StandardTypeMapper

    override fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> {
        mock.internalSql = sql
        mock.internalParams = namedParameters
        @Suppress("UNCHECKED_CAST")
        return (mock.result as List<T>?) ?: listOf()
    }

    override fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> {
        mock.internalSql = sql
        @Suppress("UNCHECKED_CAST")
        return (mock.result as List<T>?) ?: listOf()
    }

    override fun execute(sql: String, namedParameters: Map<String, Any?>): Long {
        mock.internalSql = sql
        mock.internalParams = namedParameters
        return (mock.result as Long?) ?: 0L
    }

    override fun execute(sql: String, paramSource: SqlParameterSource): Long {
        mock.internalSql = sql
        return (mock.result as Long?) ?: 0L
    }

    override fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long {
        mock.internalSql = sql
        mock.internalParams = namedParameters
        return (mock.result as Long?) ?: 0L
    }
}

// Mirrors MockExecutor onto the suspend executor surface by delegating to a blocking instance.
private class SuspendMockExecutor(mock: DatabaseMock) : SuspendSqlExecutor {
    private val delegate = MockExecutor(mock)
    override val dialect get() = delegate.dialect
    override val typeMapper get() = delegate.typeMapper

    override suspend fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> =
        delegate.execute(sql, namedParameters, handler)

    override suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        delegate.execute(sql, paramSource, handler)

    override suspend fun execute(sql: String, namedParameters: Map<String, Any?>): Long =
        delegate.execute(sql, namedParameters)

    override suspend fun execute(sql: String, paramSource: SqlParameterSource): Long =
        delegate.execute(sql, paramSource)

    override suspend fun executeUpdate(sql: String, namedParameters: Map<String, Any?>) =
        delegate.executeUpdate(sql, namedParameters)
}
