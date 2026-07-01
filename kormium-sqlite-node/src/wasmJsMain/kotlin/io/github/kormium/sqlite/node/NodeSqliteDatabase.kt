package io.github.kormium.sqlite.node

import io.github.kormium.DatabaseLifecycle
import io.github.kormium.KormiumConfig
import io.github.kormium.SqliteDialect
import io.github.kormium.StandardTypeMapper
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TransactionIsolation
import io.github.kormium.WriteListeners
import io.github.kormium.database.SuspendDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A SQLite [SuspendDatabase] for Node, backed by the synchronous better-sqlite3 driver. It
 * implements the suspend hierarchy for consistency with the other engines (the calls just don't
 * suspend). One connection; a [Mutex] serialises [useConnection] so a transaction's BEGIN…COMMIT
 * can't interleave with another's. SQLite has a single effective isolation level, so a requested
 * [TransactionIsolation] is ignored (as SqliteDialect declares).
 */
class NodeSqliteDatabase internal constructor(
    private val db: Database,
    override val config: KormiumConfig,
) : SuspendDatabase<Nothing> {

    override val writeListeners: WriteListeners = WriteListeners()
    override val dialect = SqliteDialect

    private val lifecycle = DatabaseLifecycle { db.close() }
    private val connectionLock = Mutex()

    override val isClosed: Boolean get() = lifecycle.isClosed

    private val executor = NodeSqliteExecutor(db, SqliteDialect, StandardTypeMapper)

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
            if (readOnly) executor.execute("PRAGMA query_only=ON")
            try {
                block(executor).also { executor.execute("COMMIT") }
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { executor.execute("ROLLBACK") } }
                throw e
            } finally {
                if (readOnly) runCatching { executor.execute("PRAGMA query_only=OFF") }
            }
        }
    }

    override fun close() = lifecycle.close()
}

/**
 * Opens a SQLite database via better-sqlite3 on Node.
 *
 * @param path `":memory:"` (default) for an in-memory database, or a filesystem path to persist.
 */
fun createNodeSqliteDatabase(
    path: String = ":memory:",
    config: KormiumConfig = KormiumConfig(),
): NodeSqliteDatabase = NodeSqliteDatabase(Database(path), config)
