package io.github.kormium

import io.github.kormium.database.Database
import io.github.kormium.database.SuspendDatabase

/**
 * A [Database] whose backend is known **at compile time** to be [B] — the handle type that opens
 * backend-specific DSL. Declaring a handle as this (rather than the portable `Database<G>`) is
 * what makes syntax gated on a capability resolve:
 *
 * ```kotlin
 * val db: BackendDatabase<App, PostgresBackend> = createDatabase(host, port, name, user, pass)
 * db.transaction {
 *     Jobs.find { where { Jobs.status eq ACTIVE }; limit = 10; forUpdate(LockWait.SkipLocked) }
 * }
 * ```
 *
 * Core declares this once and knows nothing about concrete backends: [B] is just a [Backend] tag,
 * and a dialect module contributes the tag (`PostgresBackend`) plus the rendering. A driver opts
 * in by implementing this interface with its own tag — that is the whole wiring per backend.
 *
 * The entry points are **members**, not extensions, on purpose. A member wins overload resolution
 * against the core `Database<G>.transaction` extension, so the same call site gets the
 * capability-carrying scope when the static type says which backend this is, and the portable one
 * otherwise. Their parameter lists mirror the extensions' exactly — a difference would break call
 * sites that use named arguments.
 *
 * [G] is phantom (it appears in no member of [Database]), so `@UnsafeVariance` adds no unsoundness
 * the covariant handle did not already have. Two consequences of the members, both of which the
 * portable extensions do not have:
 *
 *  - a member fixes the catalog to this interface's own parameter instead of inferring it per call,
 *    so pin the handle (`BackendDatabase<App, PostgresBackend>`), as the docs already recommend
 *    for `Database<App>`;
 *  - Kotlin allows `contract { callsInPlace(block, EXACTLY_ONCE) }` only on a top-level function,
 *    so these members cannot carry the contract the extensions declare. Assigning a `val` declared
 *    outside the block from inside it therefore stops compiling on a handle of this type; return
 *    the value out of the block instead (`val id = db.transaction { … }`).
 *
 * [B] is covariant, so a handle can be widened to the capability it is being used for:
 * `BackendDatabase<App, RowLockingBackend>` accepts both a Postgres and a MySQL driver, and the
 * row-locking DSL still resolves inside it.
 *
 * See [ADR 0014](https://github.com/kormium/kormium/blob/main/docs/adr/0014-typed-backend-capabilities.md).
 */
public interface BackendDatabase<out G : Catalog, out B : Backend> : Database<G> {
    @OptIn(KormiumDialectApi::class)
    public fun <R> transaction(
        isolation: TransactionIsolation? = null,
        readOnly: Boolean = false,
        block: ScopeOf<@UnsafeVariance G, B>.() -> R,
    ): R = runTransaction(isolation, readOnly, block)

    @OptIn(KormiumDialectApi::class)
    public fun <R> autocommit(block: ScopeOf<@UnsafeVariance G, B>.() -> R): R = runAutocommit(block)

    /**
     * [io.github.kormium.renderSql] for this handle — the SQL a query *would* run, with no
     * connection. Shadows the portable `Database<G>.renderSql` for the same reason the entry
     * points above do: without it the one thing most worth previewing, a backend-specific query,
     * could not be previewed at all.
     */
    @OptIn(KormiumDialectApi::class)
    public fun <R> renderSql(block: RenderScopeOf<@UnsafeVariance G, @UnsafeVariance B>.() -> R): R =
        renderSqlWith(dialect, StandardTypeMapper, block)
}

/** The suspend half of [BackendDatabase]. */
public interface SuspendBackendDatabase<out G : Catalog, out B : Backend> : SuspendDatabase<G> {
    @OptIn(KormiumDialectApi::class)
    public suspend fun <R> suspendTransaction(
        isolation: TransactionIsolation? = null,
        readOnly: Boolean = false,
        block: suspend SuspendScopeOf<@UnsafeVariance G, B>.() -> R,
    ): R = runSuspendTransaction(isolation, readOnly, block)

    @OptIn(KormiumDialectApi::class)
    public suspend fun <R> suspendAutocommit(block: suspend SuspendScopeOf<@UnsafeVariance G, B>.() -> R): R =
        runSuspendAutocommit(block)
}
