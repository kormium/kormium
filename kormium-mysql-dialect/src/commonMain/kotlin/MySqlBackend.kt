package io.github.kormium

/**
 * The capability tag a MySQL/MariaDB-backed scope carries. Like [PostgresBackend] it is phantom —
 * it exists only so that syntax MySQL can run resolves here and nowhere else.
 *
 * Row locking is shared with Postgres rather than duplicated: both tags extend the same
 * [RowLockingBackend], so `forUpdate` / `forShare` are declared once in core.
 */
public interface MySqlBackend : AnyBackend, RowLockingBackend


/** Renders MySQL-specific queries offline (no connection) — the cheap way to test them. */
@OptIn(KormiumDialectApi::class)
public fun <G : Catalog, R> renderMySqlSql(
    typeMapper: TypeMapper = StandardTypeMapper,
    block: RenderScopeOf<G, MySqlBackend>.() -> R,
): R = renderSqlWith(MySqlDialect, typeMapper, block)
