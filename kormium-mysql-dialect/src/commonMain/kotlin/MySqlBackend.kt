package io.github.kormium

import io.github.kormium.database.Database
import io.github.kormium.database.SuspendDatabase

/**
 * The capability tag a MySQL/MariaDB-backed scope carries. Like [PostgresBackend] it is phantom —
 * it exists only so that syntax MySQL can run resolves here and nowhere else.
 *
 * Row locking is shared with Postgres rather than duplicated: both tags extend the same
 * [RowLockingBackend], so `forUpdate` / `forShare` are declared once in core.
 */
public interface MySqlBackend : AnyBackend, RowLockingBackend

/**
 * A [Database] known at compile time to be MySQL/MariaDB. Declaring a handle as this type (rather
 * than the portable `Database<G>`) opens the MySQL-specific DSL:
 *
 * ```kotlin
 * val db: MySqlDatabase<App> = createDatabase(...)
 * db.transaction {
 *     Jobs.find { where { Jobs.status eq ACTIVE }; limit = 10; forUpdate(LockWait.SkipLocked) }
 * }
 * ```
 *
 * The entry points are **members** so they win overload resolution against the core
 * `Database<G>.transaction` extension; their parameter lists mirror it exactly. See
 * [PostgresDatabase] for the same pattern and the reason `@UnsafeVariance` is sound here.
 *
 * What a type still cannot promise: the server's **version**. `SKIP LOCKED` needs MySQL 8.0.1 /
 * MariaDB 10.6 — see [MySqlDialect.renderRowLock].
 */
public interface MySqlDatabase<out G : Catalog> : Database<G> {
    @OptIn(KormiumDialectApi::class)
    public fun <R> transaction(
        isolation: TransactionIsolation? = null,
        readOnly: Boolean = false,
        block: ScopeOf<@UnsafeVariance G, MySqlBackend>.() -> R,
    ): R = runTransaction(isolation, readOnly, block)

    @OptIn(KormiumDialectApi::class)
    public fun <R> autocommit(block: ScopeOf<@UnsafeVariance G, MySqlBackend>.() -> R): R =
        runAutocommit(block)
}

/** The suspend half of [MySqlDatabase]. */
public interface SuspendMySqlDatabase<out G : Catalog> : SuspendDatabase<G> {
    @OptIn(KormiumDialectApi::class)
    public suspend fun <R> suspendTransaction(
        isolation: TransactionIsolation? = null,
        readOnly: Boolean = false,
        block: suspend SuspendScopeOf<@UnsafeVariance G, MySqlBackend>.() -> R,
    ): R = runSuspendTransaction(isolation, readOnly, block)

    @OptIn(KormiumDialectApi::class)
    public suspend fun <R> suspendAutocommit(block: suspend SuspendScopeOf<@UnsafeVariance G, MySqlBackend>.() -> R): R =
        runSuspendAutocommit(block)
}

/** Renders MySQL-specific queries offline (no connection) — the cheap way to test them. */
@OptIn(KormiumDialectApi::class)
public fun <G : Catalog, R> renderMySqlSql(
    typeMapper: TypeMapper = StandardTypeMapper,
    block: RenderScopeOf<G, MySqlBackend>.() -> R,
): R = renderSqlWith(MySqlDialect, typeMapper, block)
