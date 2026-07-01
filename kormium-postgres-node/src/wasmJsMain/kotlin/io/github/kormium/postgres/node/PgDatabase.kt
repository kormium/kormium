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
import kotlinx.coroutines.withContext

/**
 * An async Postgres [SuspendDatabase] for Node, backed by a node-postgres connection [Pool]. Each
 * [useConnection] borrows a [PoolClient] for the duration of the block and returns it afterwards, so
 * independent calls run on independent connections — real concurrency, no Mutex. Suspend-only (a JS
 * event loop can't be blocked).
 */
class PgDatabase internal constructor(
    private val pool: Pool,
    override val config: KormiumConfig,
) : SuspendDatabase<Nothing> {

    override val writeListeners: WriteListeners = WriteListeners()
    override val dialect = PostgresDialect

    private val lifecycle = DatabaseLifecycle { pool.end() }

    override val isClosed: Boolean get() = lifecycle.isClosed

    override suspend fun <R> useConnection(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R {
        lifecycle.checkOpen()
        val client = pool.connect().await<PoolClient>()
        val exec = PgExecutor(client, PostgresDialect, StandardTypeMapper)
        try {
            if (transactional) {
                exec.execute("BEGIN")
                isolation?.let { exec.execute("SET TRANSACTION ISOLATION LEVEL ${it.sql}") }
                if (readOnly) exec.execute("SET TRANSACTION READ ONLY")
            }
            return try {
                block(exec).also { if (transactional) exec.execute("COMMIT") }
            } catch (e: Throwable) {
                if (transactional) withContext(NonCancellable) { runCatching { exec.execute("ROLLBACK") } }
                throw e
            }
        } finally {
            client.release()
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
 * Opens a Postgres database over a node-postgres connection pool of [poolSize] connections. Returns
 * the handle tagged [Nothing], so by covariance it pins to any `SuspendDatabase<MyCatalog>`.
 */
fun createNodePostgresDatabase(
    host: String,
    port: Int = 5432,
    database: String,
    user: String,
    password: String,
    poolSize: Int = 10,
    config: KormiumConfig = KormiumConfig(),
): PgDatabase = PgDatabase(newPgPool(host, port, database, user, password, poolSize), config)
