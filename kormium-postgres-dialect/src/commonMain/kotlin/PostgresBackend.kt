package io.github.kormium

/**
 * The capability tag a Postgres-backed scope carries: everything portable ([AnyBackend]) plus the
 * Postgres extras. Row locking ([RowLockingBackend]) is the first of them.
 *
 * It is a phantom type — it appears in no member and no value of it is ever created. Its only job
 * is to make `Jobs.find { forUpdate() }` resolve here and nowhere else.
 */
public interface PostgresBackend : AnyBackend, RowLockingBackend


/** Renders Postgres-specific queries offline (no connection) — the cheap way to test them. */
@OptIn(KormiumDialectApi::class)
public fun <G : Catalog, R> renderPostgresSql(
    typeMapper: TypeMapper = StandardTypeMapper,
    block: RenderScopeOf<G, PostgresBackend>.() -> R,
): R = renderSqlWith(PostgresDialect, typeMapper, block)

/**
 * `SELECT ... FOR NO KEY UPDATE` — an exclusive lock that still allows a concurrent `FOR KEY SHARE`,
 * so another transaction's foreign-key check against this row can proceed. Use it instead of
 * [forUpdate] when you will not change the row's key columns: it blocks strictly less.
 *
 * PostgreSQL-only, which is why it lives here and is gated on [PostgresBackend] rather than on the
 * portable [RowLockingBackend] that [forUpdate] / [forShare] use.
 */
@OptIn(KormiumDialectApi::class)
public fun SelectQueryBuilderOf<PostgresBackend>.forNoKeyUpdate(wait: LockWait = LockWait.Wait) {
    rowLock = RowLock(strength = LockStrength.NoKeyUpdate, wait = wait)
}

/**
 * `SELECT ... FOR KEY SHARE` — the weakest row lock: it blocks only changes to the row's key
 * columns (and a [forUpdate]), which is exactly what a foreign-key check takes. PostgreSQL-only;
 * see [forNoKeyUpdate].
 */
@OptIn(KormiumDialectApi::class)
public fun SelectQueryBuilderOf<PostgresBackend>.forKeyShare(wait: LockWait = LockWait.Wait) {
    rowLock = RowLock(strength = LockStrength.KeyShare, wait = wait)
}
