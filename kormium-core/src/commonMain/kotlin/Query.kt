package io.github.kormium


public data class Query(
    val whereExpression: Expression? = null,
    val limit: UInt = UInt.MAX_VALUE,
    val offset: UInt = 0u,
    val orderBy: Map<Selectable<*>, AscDescOrder>? = null,
    /** Row-level lock for this read; see [RowLock]. Null (the default) is a plain read. */
    val lock: RowLock? = null,
) {
    /**
     * Renders this query's clauses to SQL, registering any compared values as
     * bind parameters on [builder] instead of inlining them. Identifier quoting and
     * LIMIT/OFFSET rendering go through the builder's [Dialect].
     */
    public fun toSql(builder: ParamBuilder): String {
        val whereStr = whereExpression?.let { "WHERE ${it.toSql(builder)} " } ?: ""
        val orderByStr = orderBy?.let { "ORDER BY ${prepareOrderBy(it, builder)} " } ?: ""
        val limitOffsetStr = builder.dialect.renderLimitOffset(limit, offset)
        // The locking clause is the statement's tail: it follows LIMIT/OFFSET on every backend
        // that has one, and it is rendered by the dialect because the spelling differs.
        val lockStr = lock?.let { builder.dialect.renderRowLock(it) } ?: ""
        return "$whereStr$orderByStr$limitOffsetStr$lockStr"
    }

    /**
     * Renders only the `WHERE` clause (no `ORDER BY` / `LIMIT` / `OFFSET`). Used where
     * pagination and ordering must not apply: aggregates like `COUNT(*)` (an `OFFSET` would
     * skip the single aggregate row and read as 0), and `UPDATE` / `DELETE` (plain mutation
     * statements don't take `ORDER BY` / `LIMIT` / `OFFSET` in Postgres).
     *
     * A [lock] is **rejected** here rather than dropped like the ordering and pagination are.
     * Those are merely ignored on these statements, which is harmless; a lock that vanishes is
     * not — a worker would believe its rows are held when nothing holds them. The typed DSL
     * cannot even express it (only the `find` / `findOne` builder carries a lock), so this
     * catches the [Query]-value form, which any code can build.
     */
    public fun toWhereSql(builder: ParamBuilder): String {
        require(lock == null) {
            "$lock cannot be applied here: COUNT / UPDATE / DELETE render only the WHERE clause, " +
                "so the lock would be silently dropped. Take the lock with a preceding " +
                "find { } / findOne { } inside the same transaction instead."
        }
        return whereExpression?.let { "WHERE ${it.toSql(builder)} " } ?: ""
    }

    // Debug-friendly rendering; placeholders are emitted in place of values.
    //
    // A [lock] is appended from its own toString() rather than through [StandardDialect], which
    // cannot render one and would throw: a toString() that raises breaks logging, assertion
    // messages and debuggers, and can fire while another exception's message is being built.
    override fun toString(): String {
        val head = copy(lock = null).toSql(ParamBuilder(StandardDialect, StandardTypeMapper))
        return if (lock == null) head else "$head$lock"
    }

    private fun prepareOrderBy(orderBy: Map<Selectable<*>, AscDescOrder>, builder: ParamBuilder): String =
        orderBy.entries.joinToString(",") { (key, value) -> "${key.toSql(builder)} ${value.name}" }
}

public enum class AscDescOrder{
    ASC, DESC
}
