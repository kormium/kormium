package io.github.kormium.postgres.node

import io.github.kormium.DatabaseLifecycle
import io.github.kormium.KormiumConfig
import io.github.kormium.PostgresDialect
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
 * An async Postgres [SuspendDatabase] for Node, backed by one node-postgres [Client]. It implements
 * only the suspend hierarchy (no blocking on a JS event loop). One client is one connection, so a
 * [Mutex] serialises [useConnection] to keep a transaction's BEGIN…COMMIT from interleaving.
 */
class PgDatabase internal constructor(
    private val client: Client,
    override val config: KormiumConfig,
) : SuspendDatabase<Nothing> {

    override val writeListeners: WriteListeners = WriteListeners()

    private val lifecycle = DatabaseLifecycle { client.end() }
    private val connectionLock = Mutex()

    override val isClosed: Boolean get() = lifecycle.isClosed

    private val executor = PgExecutor(client, PostgresDialect, StandardTypeMapper)

    override suspend fun <R> useConnection(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R {
        lifecycle.checkOpen()
        return connectionLock.withLock {
            if (!transactional) return@withLock block(executor)
            executor.execute("BEGIN")
            isolation?.let { executor.execute("SET TRANSACTION ISOLATION LEVEL ${it.sql}") }
            if (readOnly) executor.execute("SET TRANSACTION READ ONLY")
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

/**
 * Opens a Postgres database over node-postgres. Returns the handle tagged [Nothing], so by
 * covariance it pins to any `SuspendDatabase<MyCatalog>` at the call site.
 */
suspend fun createNodePostgresDatabase(
    host: String,
    port: Int = 5432,
    database: String,
    user: String,
    password: String,
    config: KormiumConfig = KormiumConfig(),
): PgDatabase {
    val client = Client(pgClientConfig(host, port, database, user, password))
    client.connect().await<JsAny?>()
    return PgDatabase(client, config)
}
