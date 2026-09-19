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
