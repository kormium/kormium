package io.github.kormium.database

import io.github.kormium.Catalog
import io.github.kormium.Dialect
import io.github.kormium.KormiumConfig
import io.github.kormium.StandardDialect
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TransactionIsolation
import io.github.kormium.WriteListeners

/**
 * The suspend counterpart of [Database], tagged with the [Catalog] [G] it connects to.
 *
 * It is a SIBLING of [Database], NOT a subtype: a truly async backend (e.g. r2dbc)
 * cannot provide the blocking [Database.usePinned], so the two hierarchies stand
 * apart. A blocking backend (JDBC/SQLite) may implement BOTH; an async backend
 * implements only this one.
 */
interface SuspendDatabase<out G : Catalog> : AutoCloseable {
    /** Per-database configuration; defaults to [KormiumConfig] defaults unless a backend overrides it. */
    val config: KormiumConfig get() = KormiumConfig()

    /**
     * The SQL dialect this database renders to. Backends override it with their concrete dialect;
     * the neutral [StandardDialect] default keeps custom implementations compiling. Read it to render
     * a query for this backend offline: `renderSql(App, db.dialect) { ... }`.
     */
    val dialect: Dialect get() = StandardDialect

    /**
     * The write-notification registry for this database. The default [WriteListeners.Disabled]
     * means change observation (e.g. `kormium-observe`) does nothing; a backend opts in by
     * overriding this with a real [WriteListeners] instance.
     */
    val writeListeners: WriteListeners get() = WriteListeners.Disabled

    /**
     * Pins one connection for the duration of [block]; the [SuspendSqlExecutor] passed
     * to it routes every statement to that connection. Wraps BEGIN/COMMIT/ROLLBACK when
     * [transactional] is true, otherwise runs in autocommit. When transactional, [isolation]
     * (if non-null) and [readOnly] are applied to the opened transaction; both are ignored in
     * autocommit. Backend-specific.
     */
    suspend fun <R> useConnection(
        transactional: Boolean,
        isolation: TransactionIsolation? = null,
        readOnly: Boolean = false,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R

    /**
     * Whether [close] has been called. Defaults to `false` for backends that do not track it.
     * Once `true`, [useConnection] (and any `suspendTransaction` / `suspendAutocommit`) throws
     * [io.github.kormium.DatabaseClosedException].
     */
    val isClosed: Boolean get() = false

    /**
     * Closes the underlying connection(s); the database is unusable afterwards. Same contract as
     * [Database.close]: idempotent, and use-after-close throws
     * [io.github.kormium.DatabaseClosedException] uniformly across backends.
     */
    override fun close()
}
