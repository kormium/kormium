package io.github.kormium

import io.github.kormium.database.Database

/**
 * The SQL a query would run, as a string plus its bound parameters — produced by [renderSql]
 * without touching a database. The parameter values are neutral representations (the SQL string
 * is faithful to the dialect; only the displayed values go through [StandardTypeMapper]).
 */
public class RenderedSql(public val sql: String, public val params: Map<String, Any?>) {
    internal constructor(rendered: Pair<String, Map<String, Any?>>) : this(rendered.first, rendered.second)

    override fun toString(): String = if (params.isEmpty()) sql else "$sql\nparams: $params"
}

/**
 * Renders queries to their [RenderedSql] without a connection. It mirrors [Scope]'s operation
 * surface, but every operation returns the SQL it *would* run instead of executing it — the same
 * call-site syntax (`Users.find { where { } }`), a different return type. Obtain one via the
 * top-level [renderSql] (offline, explicit dialect) or [Database.renderSql] (the backend's dialect).
 */
public class RenderScope<G : Catalog> internal constructor(public val dialect: Dialect, public val typeMapper: TypeMapper) {

    // ---- reads ----
    public fun <T : Entity> Table<G, T>.find(query: Query): RenderedSql = RenderedSql(selectSql(query, dialect, typeMapper))
    public fun <T : Entity> Table<G, T>.find(block: QueryBuilder.() -> Unit): RenderedSql = find(QueryBuilder().apply(block).build())
    public fun <T : Entity> Table<G, T>.findOne(query: Query): RenderedSql = RenderedSql(selectSql(query.copy(limit = 1u), dialect, typeMapper))
    public fun <T : Entity> Table<G, T>.findOne(block: QueryBuilder.() -> Unit): RenderedSql = findOne(QueryBuilder().apply(block).build())
    public fun <T : Entity> Table<G, T>.all(): RenderedSql = RenderedSql(selectAllSql(dialect) to emptyMap())
    public fun <T : Entity> Table<G, T>.count(query: Query = Query()): RenderedSql = RenderedSql(countSql(query, dialect, typeMapper))
    public fun <T : Entity> Table<G, T>.count(block: QueryBuilder.() -> Unit): RenderedSql = count(QueryBuilder().apply(block).build())

    // ---- writes ----
    public fun <T : Entity> Table<G, T>.insert(entity: T, returning: Boolean = false): RenderedSql =
        RenderedSql(insertSql(entity, dialect, typeMapper, returning))

    /** A batch insert may split into several statements (per [BatchInsertMode]), so this returns one [RenderedSql] each. */
    public fun <T : Entity> Table<G, T>.insertAll(
        entities: List<T>,
        returning: Boolean = false,
        batchInsertMode: BatchInsertMode = BatchInsertMode.GroupByAssignedFields,
    ): List<RenderedSql> =
        buildBatchStatements(entities, batchInsertMode, dialect, typeMapper, returning).map { RenderedSql(it.sql, it.params) }

    public fun <T : Entity> Table<G, T>.upsert(entity: T, onConflict: Column<*, *, T>, update: T, returning: Boolean = false): RenderedSql =
        upsert(entity, listOf(onConflict), update, returning)

    public fun <T : Entity> Table<G, T>.upsert(entity: T, onConflict: List<Column<*, *, T>>, update: T, returning: Boolean = false): RenderedSql =
        RenderedSql(upsertSql(entity, onConflict, update, dialect, typeMapper, returning))

    public fun <T : Entity> Table<G, T>.insertOrIgnore(entity: T, onConflict: Column<*, *, T>): RenderedSql =
        insertOrIgnore(entity, listOf(onConflict))

    public fun <T : Entity> Table<G, T>.insertOrIgnore(entity: T, onConflict: List<Column<*, *, T>>): RenderedSql =
        RenderedSql(insertOrIgnoreSql(entity, onConflict, dialect, typeMapper))

    public fun <T : Entity> Table<G, T>.update(entity: T, query: Query): RenderedSql =
        RenderedSql(updateSql(query, entity, dialect, typeMapper))

    public fun <T : Entity> Table<G, T>.update(entity: T, block: QueryBuilder.() -> Unit): RenderedSql =
        update(entity, QueryBuilder().apply(block).build())

    public fun <T : Entity> Table<G, T>.update(block: UpdateBuilder.() -> Unit): RenderedSql {
        val builder = UpdateBuilder().apply(block)
        return RenderedSql(updateSql(builder.buildWhere(), builder.buildAssignments(), dialect, typeMapper))
    }

    public fun <T : Entity> Table<G, T>.deleteWhere(query: Query): RenderedSql = RenderedSql(deleteSql(query, dialect, typeMapper))
    public fun <T : Entity> Table<G, T>.deleteWhere(block: QueryBuilder.() -> Unit): RenderedSql =
        deleteWhere(QueryBuilder().apply(block).build())

    // ---- joins (SQL-producing form only; result-mapping variants render the same SQL) ----
    public fun Join<G>.select(vararg fields: Selectable<*>): RenderedSql =
        RenderedSql(buildSelect(this, if (fields.isEmpty()) allColumns() else fields.toList(), dialect, typeMapper))

    public fun <A : Entity, B : Entity> JoinPair<G, A, B>.select(vararg fields: Selectable<*>): RenderedSql = asJoin().select(*fields)
    public fun <A : Entity, B : Entity> LeftJoinPair<G, A, B>.select(vararg fields: Selectable<*>): RenderedSql = asJoin().select(*fields)
}

/**
 * Renders queries to their SQL without a connection, for the given [dialect]. The [block] reads
 * exactly like a `transaction { }` / `autocommit { }` body, but each operation returns its
 * [RenderedSql] instead of running:
 *
 * ```kotlin
 * val r = renderSql(App, PostgresDialect) { Users.find { where { Users.age gtEq 18 } } }
 * println(r.sql)     // SELECT ... FROM "users" WHERE "age" >= :p0 ...
 * println(r.params)  // {p0=18}
 * ```
 *
 * [catalog] is the [Catalog] object (`App`) — passed so the catalog tag [G] is inferred, the same
 * tag a `Database<App>` pins; it is not otherwise used.
 */
public fun <G : Catalog, R> renderSql(
    catalog: G,
    dialect: Dialect = StandardDialect,
    typeMapper: TypeMapper = StandardTypeMapper,
    block: RenderScope<G>.() -> R,
): R = RenderScope<G>(dialect, typeMapper).block()

/** Renders queries using this database's own [Dialect], so the preview matches the real backend. */
public fun <G : Catalog, R> Database<G>.renderSql(block: RenderScope<G>.() -> R): R =
    RenderScope<G>(dialect, StandardTypeMapper).block()
