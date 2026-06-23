package io.github.kormium.mysql.node

import io.github.kormium.DatabaseLifecycle
import io.github.kormium.KormiumConfig
import io.github.kormium.MySqlDialect
import io.github.kormium.StandardTypeMapper
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TransactionIsolation
import io.github.kormium.WriteListeners
import io.github.kormium.database.SuspendDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * An async MySQL/MariaDB [SuspendDatabase] for Node, backed by one mysql2 connection. Suspend-only
 * (no blocking on a JS event loop); one connection, so a [Mutex] serialises [useConnection].
 */
class MySqlDatabase internal constructor(
    private val connection: MySqlConnection,
    override val config: KormiumConfig,
) : SuspendDatabase<Nothing> {

    override val writeListeners: WriteListeners = WriteListeners()

    private val lifecycle = DatabaseLifecycle { connection.end() }
    private val connectionLock = Mutex()

    override val isClosed: Boolean get() = lifecycle.isClosed

    private val executor = MySqlExecutor(connection, MySqlDialect, StandardTypeMapper)

    override suspend fun <R> useConnection(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R {
        lifecycle.checkOpen()
        return connectionLock.withLock {
            if (!transactional) return@withLock block(executor)
            // MySQL applies SET TRANSACTION to the next transaction, so set isolation before START.
            isolation?.let { executor.execute("SET TRANSACTION ISOLATION LEVEL ${it.sql}") }
            executor.execute(if (readOnly) "START TRANSACTION READ ONLY" else "START TRANSACTION")
            try {
                block(executor).also { executor.execute("COMMIT") }
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { executor.execute("ROLLBACK") } }
                throw e
            }
        }
    }

    override fun close() = lifecycle.close()
}

private val TransactionIsolation.sql: String
    get() = when (this) {
        TransactionIsolation.ReadUncommitted -> "READ UNCOMMITTED"
        TransactionIsolation.ReadCommitted -> "READ COMMITTED"
        TransactionIsolation.RepeatableRead -> "REPEATABLE READ"
        TransactionIsolation.Serializable -> "SERIALIZABLE"
    }

/** Opens a MySQL/MariaDB database over mysql2. Tagged [Nothing]; pin the catalog at the call site. */
suspend fun createNodeMysqlDatabase(
    host: String,
    port: Int = 3306,
    database: String,
    user: String,
    password: String,
    config: KormiumConfig = KormiumConfig(),
): MySqlDatabase {
    val connection = createConnection(mysqlConfig(host, port, database, user, password)).await<MySqlConnection>()
    return MySqlDatabase(connection, config)
}
