package io.github.kormium


public data class Query(
    val whereExpression: Expression? = null,
    val limit: UInt = UInt.MAX_VALUE,
    val offset: UInt = 0u,
    val orderBy: Map<Selectable<*>, AscDescOrder>? = null
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
        return "$whereStr$orderByStr$limitOffsetStr"
    }

    /**
     * Renders only the `WHERE` clause (no `ORDER BY` / `LIMIT` / `OFFSET`). Used where
     * pagination and ordering must not apply: aggregates like `COUNT(*)` (an `OFFSET` would
     * skip the single aggregate row and read as 0), and `UPDATE` / `DELETE` (plain mutation
     * statements don't take `ORDER BY` / `LIMIT` / `OFFSET` in Postgres).
     */
    public fun toWhereSql(builder: ParamBuilder): String =
        whereExpression?.let { "WHERE ${it.toSql(builder)} " } ?: ""

    // Debug-friendly rendering; placeholders are emitted in place of values.
    override fun toString(): String = toSql(ParamBuilder(StandardDialect, StandardTypeMapper))

    private fun prepareOrderBy(orderBy: Map<Selectable<*>, AscDescOrder>, builder: ParamBuilder): String =
        orderBy.entries.joinToString(",") { (key, value) -> "${key.toSql(builder)} ${value.name}" }
}

public enum class AscDescOrder{
    ASC, DESC
}
