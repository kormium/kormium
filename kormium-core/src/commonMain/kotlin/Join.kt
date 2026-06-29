package io.github.kormium

import io.github.kormium.resultset.ResultSet

/**
 * Something that can appear in a SELECT list and be read back from a [ResultRow]: a
 * [Column] or an aggregate (see Aggregate.kt). It is also an [Expression] (its [toSql]
 * is the SELECT-list SQL), so aggregates can be used in `having(...)`. [Z] is the value
 * type read.
 */
interface Selectable<Z> : Expression {
    /** Reads this field's value from the result row at [index]. */
    fun read(rs: ResultSet, index: Int, typeMapper: TypeMapper): Z?

    /**
     * A stable, structural key identifying this field in a [ResultRow] — independent of the
     * instance and the dialect. Two selectables that select the same thing share a key, so a row
     * can be read with a freshly built field (`row[Orders.total.sum()]`) without hoisting it into
     * a `val`. Columns key by `table.name`; aggregates by their function over their target's key.
     */
    fun resultKey(): Any
}

internal enum class JoinType(val sql: String) { INNER("INNER JOIN"), LEFT("LEFT JOIN") }

internal class JoinClause<G : Catalog>(val type: JoinType, val table: Table<G, *>, val on: Expression)

/**
 * A query over one or more tables. Read it inside a scope with `select(...)` (returning
 * [ResultRow]s) or `select(...) { row -> ... }` (mapping each row). A two-table join built
 * from two tables ([JoinPair]) can also be read as entity pairs with `find()`. Supports
 * `where`, `groupBy`, `having` and `distinct`.
 */
class Join<G : Catalog> internal constructor(
    internal val base: Table<G, *>,
    internal val clauses: List<JoinClause<G>>,
    internal val whereExpr: Expression?,
    internal val groupByCols: List<Column<*, *, *>> = emptyList(),
    internal val havingExpr: Expression? = null,
    internal val distinct: Boolean = false,
) {
    /** All tables in the join, base first. */
    internal val tables: List<Table<G, *>> get() = listOf(base) + clauses.map { it.table }

    private fun copy(
        whereExpr: Expression? = this.whereExpr,
        clauses: List<JoinClause<G>> = this.clauses,
        groupByCols: List<Column<*, *, *>> = this.groupByCols,
        havingExpr: Expression? = this.havingExpr,
        distinct: Boolean = this.distinct,
    ) = Join(base, clauses, whereExpr, groupByCols, havingExpr, distinct)

    /** Restricts the joined rows; combined with AND if called more than once. */
    fun where(condition: Expression): Join<G> = copy(whereExpr = whereExpr?.let { it and condition } ?: condition)

    /** Groups rows by [columns] (for use with aggregates). */
    fun groupBy(vararg columns: Column<*, *, *>): Join<G> = copy(groupByCols = groupByCols + columns)

    /** Filters groups (combined with AND if called more than once). */
    fun having(condition: Expression): Join<G> = copy(havingExpr = havingExpr?.let { it and condition } ?: condition)

    /** Selects distinct rows. */
    fun distinct(): Join<G> = copy(distinct = true)

    infix fun innerJoin(other: Table<G, *>): JoinStep<G> = JoinStep(this, JoinType.INNER, other)
    infix fun leftJoin(other: Table<G, *>): JoinStep<G> = JoinStep(this, JoinType.LEFT, other)
}

/** Starts a query over a single table (for aggregates / grouping / distinct). */
fun <G : Catalog> Table<G, *>.query(): Join<G> = Join(this, emptyList(), null)

/** A pending (erased) join awaiting its ON condition. */
class JoinStep<G : Catalog> internal constructor(
    private val join: Join<G>,
    private val type: JoinType,
    private val table: Table<G, *>,
) {
    infix fun on(condition: Expression): Join<G> =
        Join(join.base, join.clauses + JoinClause(type, table, condition), join.whereExpr, join.groupByCols, join.havingExpr, join.distinct)
}

/**
 * A two-table join that keeps both entity types, so it can be read as entity pairs
 * (`find()`) in addition to the `select(...)` forms. Joining a third table — or grouping —
 * degrades to an (erased) [Join] that supports only the `select(...)` forms.
 */
class JoinPair<G : Catalog, A : Entity, B : Entity> internal constructor(
    internal val left: Table<G, A>,
    internal val right: Table<G, B>,
    private val type: JoinType,
    private val on: Expression,
    internal val whereExpr: Expression?,
) {
    fun where(condition: Expression): JoinPair<G, A, B> =
        JoinPair(left, right, type, on, whereExpr?.let { it and condition } ?: condition)

    internal fun asJoin(): Join<G> = Join(left, listOf(JoinClause(type, right, on)), whereExpr)

    fun groupBy(vararg columns: Column<*, *, *>): Join<G> = asJoin().groupBy(*columns)
    fun distinct(): Join<G> = asJoin().distinct()

    infix fun innerJoin(other: Table<G, *>): JoinStep<G> = asJoin().innerJoin(other)
    infix fun leftJoin(other: Table<G, *>): JoinStep<G> = asJoin().leftJoin(other)
}

/**
 * A two-table LEFT join that keeps both entity types. Unlike [JoinPair], its `find()` (on
 * [Scope] / [SuspendScope]) returns `Pair<A, B?>` — the right side is `null` for left rows
 * with no match. Joining a third table — or grouping — degrades to an (erased) [Join] that
 * supports only the `select(...)` forms.
 */
class LeftJoinPair<G : Catalog, A : Entity, B : Entity> internal constructor(
    internal val left: Table<G, A>,
    internal val right: Table<G, B>,
    private val on: Expression,
    internal val whereExpr: Expression?,
) {
    fun where(condition: Expression): LeftJoinPair<G, A, B> =
        LeftJoinPair(left, right, on, whereExpr?.let { it and condition } ?: condition)

    internal fun asJoin(): Join<G> = Join(left, listOf(JoinClause(JoinType.LEFT, right, on)), whereExpr)

    fun groupBy(vararg columns: Column<*, *, *>): Join<G> = asJoin().groupBy(*columns)
    fun distinct(): Join<G> = asJoin().distinct()

    infix fun innerJoin(other: Table<G, *>): JoinStep<G> = asJoin().innerJoin(other)
    infix fun leftJoin(other: Table<G, *>): JoinStep<G> = asJoin().leftJoin(other)
}

/** A two-table join awaiting its ON condition, keeping both entity types. */
class JoinPairStep<G : Catalog, A : Entity, B : Entity> internal constructor(
    private val left: Table<G, A>,
    private val right: Table<G, B>,
    private val type: JoinType,
) {
    infix fun on(condition: Expression): JoinPair<G, A, B> = JoinPair(left, right, type, condition, null)
}

/** A two-table LEFT join awaiting its ON condition, keeping both entity types. */
class LeftJoinPairStep<G : Catalog, A : Entity, B : Entity> internal constructor(
    private val left: Table<G, A>,
    private val right: Table<G, B>,
) {
    infix fun on(condition: Expression): LeftJoinPair<G, A, B> = LeftJoinPair(left, right, condition, null)
}

infix fun <G : Catalog, A : Entity, B : Entity> Table<G, A>.innerJoin(other: Table<G, B>): JoinPairStep<G, A, B> =
    JoinPairStep(this, other, JoinType.INNER)

infix fun <G : Catalog, A : Entity, B : Entity> Table<G, A>.leftJoin(other: Table<G, B>): LeftJoinPairStep<G, A, B> =
    LeftJoinPairStep(this, other)

/**
 * One row of a query result. Read fields by the [Selectable] you selected:
 * `row[Users.name]` / `row[total]` (throws if NULL/absent) or `row.getOrNull(...)`.
 */
class ResultRow internal constructor(private val values: Map<Any, Any?>) {
    operator fun <Z> get(field: Selectable<Z>): Z {
        val key = fieldKey(field)
        if (key !in values) {
            error("Field '$key' was not selected in this projection — add it to select(...), or use getOrNull.")
        }
        @Suppress("UNCHECKED_CAST")
        return (values[key] as Z?)
            ?: error("Selected field '$key' is NULL; use getOrNull for nullable fields.")
    }

    fun <Z> getOrNull(field: Selectable<Z>): Z? {
        @Suppress("UNCHECKED_CAST")
        return values[fieldKey(field)] as Z?
    }

    // Key-based accessors (the key is computed once by the caller, avoiding a second fieldKey()
    // string-concat per column). containsKey distinguishes a selected-but-NULL value from a field
    // the projection never selected, so hydrate can give the right diagnostic.
    internal fun containsKey(key: Any): Boolean = key in values
    internal fun getByKey(key: Any): Any? = values[key]
}

// Identifies a selected field by its structural key, so a row reads back regardless of which
// instance is used — both a Column's property delegate and an aggregate factory yield a fresh
// instance on each access, so instance identity can't be used.
internal fun fieldKey(field: Selectable<*>): Any = field.resultKey()

// Qualifies a table reference.
internal fun Table<*, *>.qualifiedName(dialect: Dialect): String {
    return dialect.quoteIdentifier(tableName)
}

internal fun Join<*>.allColumns(): List<Column<*, *, *>> =
    tables.flatMap { it.getFieldDisplayNames().values }

// Builds the SELECT SQL + params (columns are emitted qualified, e.g. "users"."id",
// so colliding names don't clash). Pure — no I/O; the runners below execute it.
private fun buildSelect(
    join: Join<*>,
    fields: List<Selectable<*>>,
    dialect: Dialect,
    typeMapper: TypeMapper,
): Pair<String, Map<String, Any?>> {
    val builder = ParamBuilder(dialect, typeMapper, qualifyColumns = true)
    val selectList = fields.joinToString(", ") { it.toSql(builder) }
    val from = StringBuilder(join.base.qualifiedName(builder.dialect))
    for (clause in join.clauses) {
        from.append(" ${clause.type.sql} ${clause.table.qualifiedName(builder.dialect)} ON ${clause.on.toSql(builder)}")
    }
    val distinct = if (join.distinct) "DISTINCT " else ""
    val where = join.whereExpr?.let { " WHERE ${it.toSql(builder)}" }.orEmpty()
    val groupBy = if (join.groupByCols.isEmpty()) ""
        else " GROUP BY ${join.groupByCols.joinToString(", ") { it.toSql(builder) }}"
    val having = join.havingExpr?.let { " HAVING ${it.toSql(builder)}" }.orEmpty()
    return "SELECT $distinct$selectList FROM $from$where$groupBy$having" to builder.params
}

// Reads each field positionally into a ResultRow.
private fun mapRow(fields: List<Selectable<*>>, rs: ResultSet, typeMapper: TypeMapper): ResultRow =
    ResultRow(fields.withIndex().associate { (i, f) -> fieldKey(f) to readSelectable(f, i, rs, typeMapper) })

// Reads one selected field, wrapping any backend/conversion failure in a ResultMappingException
// that names the source (column+table+expected type, or the expression) and preserves the cause.
private fun readSelectable(field: Selectable<*>, index: Int, rs: ResultSet, typeMapper: TypeMapper): Any? =
    try {
        field.read(rs, index, typeMapper)
    } catch (e: ResultMappingException) {
        throw e
    } catch (e: Throwable) {
        val what = if (field is Column<*, *, *>) {
            "column '${field.name}' of table '${field.tableRef.tableName}' (expected Korm type ${field.columnType.description})"
        } else {
            "selected expression"
        }
        throw ResultMappingException("Failed to read $what at result index $index: ${e.message}", cause = e)
    }

// ---- entity-pair hydration (shared by Scope and SuspendScope) ----

// The SELECT list for a two-table entity-pair read: every column of both tables.
internal fun pairSelectFields(left: Table<*, *>, right: Table<*, *>): List<Selectable<*>> =
    (left.getFieldDisplayNames().values + right.getFieldDisplayNames().values).toList()

private fun <T : Entity> Table<*, T>.hydrateFrom(row: ResultRow): T {
    val columns = getFieldDisplayNames()
    // One pass, one map allocation, one fieldKey() per column; the absent set is allocated only if
    // a column is actually missing (the common all-columns-selected case stays allocation-free).
    val fields = HashMap<String, Any?>(columns.size * 2)
    var absent: MutableSet<String>? = null
    for ((fieldName, c) in columns) {
        val key = fieldKey(c)
        if (!row.containsKey(key)) (absent ?: mutableSetOf<String>().also { absent = it }).add(fieldName)
        fields[fieldName] = row.getByKey(key)
    }
    return hydrate(fields, absentFields = absent ?: emptySet())
}

internal fun <A : Entity, B : Entity> hydrateInnerPairs(
    left: Table<*, A>,
    right: Table<*, B>,
    rows: List<ResultRow>,
): List<Pair<A, B>> = rows.map { row -> left.hydrateFrom(row) to right.hydrateFrom(row) }

// A LEFT JOIN row with no right-side match reads NULL in the right table's primary key —
// impossible for a matched row, since primary keys are non-null by construction. For a
// table without a resolvable primary key, fall back to "every right column is NULL"
// (only wrong for a matched row consisting entirely of NULLs, which has no identity anyway).
internal fun <A : Entity, B : Entity> hydrateLeftPairs(
    left: Table<*, A>,
    right: Table<*, B>,
    rows: List<ResultRow>,
): List<Pair<A, B?>> {
    val rightKey: List<Column<*, *, *>> =
        right.primaryKey.ifEmpty { right.getFieldDisplayNames().values.toList() }
    return rows.map { row ->
        val missing = rightKey.all { row.getOrNull(it) == null }
        left.hydrateFrom(row) to if (missing) null else right.hydrateFrom(row)
    }
}

internal fun runSelect(exec: SqlExecutor, join: Join<*>, fields: List<Selectable<*>>): List<ResultRow> {
    val (sql, params) = buildSelect(join, fields, exec.dialect, exec.typeMapper)
    return exec.execute(sql, params) { rs -> mapRow(fields, rs, exec.typeMapper) }
}

internal suspend fun runSelect(exec: SuspendSqlExecutor, join: Join<*>, fields: List<Selectable<*>>): List<ResultRow> {
    val (sql, params) = buildSelect(join, fields, exec.dialect, exec.typeMapper)
    return exec.execute(sql, params) { rs -> mapRow(fields, rs, exec.typeMapper) }
}
