package io.github.kormium

import io.github.kormium.database.Database
import io.github.kormium.database.SuspendDatabase

/**
 * The capability tag a Postgres-backed scope carries: everything portable ([AnyBackend]) plus the
 * Postgres extras. Row locking ([RowLockingBackend]) is the first of them.
 *
 * It is a phantom type — it appears in no member and no value of it is ever created. Its only job
 * is to make `Jobs.find { forUpdate() }` resolve here and nowhere else.
 */
public interface PostgresBackend : AnyBackend, RowLockingBackend

/**
 * A [Database] known **at compile time** to be Postgres. Declaring a handle as this type (rather
 * than the portable `Database<G>`) is what opens the Postgres-specific DSL:
 *
 * ```kotlin
 * val db: PostgresDatabase<App> = createPostgresDatabase(...)
 * db.transaction {
 *     Jobs.find { where { Jobs.status eq ACTIVE }; limit = 10; forUpdate(LockWait.SkipLocked) }
 * }
 * ```
 *
 * The entry points below are **members**, not extensions, on purpose: a member wins overload
 * resolution against the core `Database<G>.transaction` extension, so the same call site quietly
 * gets the richer scope when the static type is known to be Postgres, and the portable one
 * otherwise. Their parameter lists must stay identical to the core extensions', or a call using
 * named arguments would break.
 *
 * [G] is phantom (it appears in no member of [Database]), so `@UnsafeVariance` here adds no
 * unsoundness that the covariant handle did not already have.
 */
public interface PostgresDatabase<out G : Catalog> : Database<G> {
    @OptIn(KormiumDialectApi::class)
    public fun <R> transaction(
        isolation: TransactionIsolation? = null,
        readOnly: Boolean = false,
        block: ScopeOf<@UnsafeVariance G, PostgresBackend>.() -> R,
    ): R = runTransaction(isolation, readOnly, block)

    @OptIn(KormiumDialectApi::class)
    public fun <R> autocommit(block: ScopeOf<@UnsafeVariance G, PostgresBackend>.() -> R): R =
        runAutocommit(block)
}

/** The suspend half of [PostgresDatabase]. */
public interface SuspendPostgresDatabase<out G : Catalog> : SuspendDatabase<G> {
    @OptIn(KormiumDialectApi::class)
    public suspend fun <R> suspendTransaction(
        isolation: TransactionIsolation? = null,
        readOnly: Boolean = false,
        block: suspend SuspendScopeOf<@UnsafeVariance G, PostgresBackend>.() -> R,
    ): R = runSuspendTransaction(isolation, readOnly, block)

    @OptIn(KormiumDialectApi::class)
    public suspend fun <R> suspendAutocommit(block: suspend SuspendScopeOf<@UnsafeVariance G, PostgresBackend>.() -> R): R =
        runSuspendAutocommit(block)
}

/** Renders Postgres-specific queries offline (no connection) — the cheap way to test them. */
@OptIn(KormiumDialectApi::class)
public fun <G : Catalog, R> renderPostgresSql(
    typeMapper: TypeMapper = StandardTypeMapper,
    block: RenderScopeOf<G, PostgresBackend>.() -> R,
): R = renderSqlWith(PostgresDialect, typeMapper, block)
