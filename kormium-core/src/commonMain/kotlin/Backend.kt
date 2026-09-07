package io.github.kormium

/**
 * Phantom tag describing **what the database behind a scope can do**, carried by [ScopeOf] and
 * [QueryBuilderOf] so backend-specific syntax can be gated at compile time.
 *
 * A tag never appears in a member — it exists only so a dialect module can publish DSL that does
 * not resolve on a backend which cannot run it:
 *
 * ```kotlin
 * // in kormium-postgres-dialect
 * public interface PostgresBackend : AnyBackend, RowLockingBackend
 * // core, gated on the capability rather than on the engine
 * public fun QueryBuilderOf<RowLockingBackend>.forUpdate(...)
 * ```
 *
 * Capability tags ([RowLockingBackend], …) are declared here in core when the feature is portable
 * across several engines; an engine-only extra declares its own tag in its dialect module.
 */
public interface Backend

/**
 * The portable baseline — everything every supported backend can do, and the tag a plain
 * [transaction] / [autocommit] scope carries. A dialect's own tag extends this, so a backend scope
 * is usable everywhere a portable one is expected (the tag is covariant).
 */
public interface AnyBackend : Backend

/**
 * Capability: the backend supports row-level locking reads (`SELECT ... FOR UPDATE` / `FOR SHARE`).
 * Postgres and MySQL declare it; SQLite does not, so [forUpdate] / [forShare] do not compile there.
 */
public interface RowLockingBackend : Backend
