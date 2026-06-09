package io.github.knyazevs.korm

import io.github.knyazevs.korm.resultset.ResultSet
import kotlin.jvm.JvmName

// Aggregates are Selectable (appear in SELECT and are read from a ResultRow) and, since
// Selectable is an Expression, can also appear in `having(...)` (e.g. `total gt Value(100)`).

/** `COUNT(*)` — the number of rows in the group. Hold the result in a `val` to read it. */
fun count(): Selectable<Long> = object : Selectable<Long> {
    override fun toSql(builder: ParamBuilder) = "COUNT(*)"
    override fun read(rs: ResultSet, index: kotlin.Int, typeMapper: TypeMapper): Long? = rs.getLong(index)
}

/** `COUNT(column)` — the non-null values of the column in the group. */
fun Column<*, *, *>.count(): Selectable<Long> {
    val column = this
    return object : Selectable<Long> {
        override fun toSql(builder: ParamBuilder) = "COUNT(${column.toSql(builder)})"
        override fun read(rs: ResultSet, index: kotlin.Int, typeMapper: TypeMapper): Long? = rs.getLong(index)
    }
}

// MIN/MAX keep the column's type (read through its type mapping) — MIN/MAX of an integer is
// still that integer. SUM is different: SUM over an integer column returns a wider type
// server-side (Postgres: bigint), so reading it back through the Int mapping would overflow
// (toInt() throws). The integer-family sum() overloads below therefore return Selectable<Long>
// and read the aggregate as a Long; SUM of a BigDecimal/Double column keeps the column type
// via the generic sum().
fun <Z> Column<Z, *, *>.min(): Selectable<Z> = ColumnAggregate("MIN", this)
fun <Z> Column<Z, *, *>.max(): Selectable<Z> = ColumnAggregate("MAX", this)

/** `SUM(column)` for a non-integer column (e.g. BigDecimal/Double), read through its type. */
fun <Z> Column<Z, *, *>.sum(): Selectable<Z> = ColumnAggregate("SUM", this)

// More-specific sum() overloads for integer columns: they win overload resolution over the
// generic sum() above and return Selectable<Long>, reading the (bigint) aggregate as a Long so
// sums beyond Int.MAX_VALUE don't overflow. @JvmName disambiguates the otherwise-identical JVM
// signatures (generic erasure collides with these).
@JvmName("sumInt")
fun Column<kotlin.Int, *, *>.sum(): Selectable<Long> = LongAggregate(this)

@JvmName("sumShort")
fun Column<kotlin.Short, *, *>.sum(): Selectable<Long> = LongAggregate(this)

@JvmName("sumLong")
fun Column<kotlin.Long, *, *>.sum(): Selectable<Long> = LongAggregate(this)

private class ColumnAggregate<Z>(private val fn: String, private val column: Column<Z, *, *>) : Selectable<Z> {
    override fun toSql(builder: ParamBuilder) = "$fn(${column.toSql(builder)})"
    override fun read(rs: ResultSet, index: kotlin.Int, typeMapper: TypeMapper): Z? = column.read(rs, index, typeMapper)
}

// SUM(column) read as a Long (the server-side bigint width), regardless of the column's own type.
private class LongAggregate(private val column: Column<*, *, *>) : Selectable<Long> {
    override fun toSql(builder: ParamBuilder) = "SUM(${column.toSql(builder)})"
    override fun read(rs: ResultSet, index: kotlin.Int, typeMapper: TypeMapper): Long? = rs.getLong(index)
}

/** `AVG(column)` as a Double. */
fun Column<*, *, *>.avg(): Selectable<Double> {
    val column = this
    return object : Selectable<Double> {
        override fun toSql(builder: ParamBuilder) = "AVG(${column.toSql(builder)})"
        override fun read(rs: ResultSet, index: kotlin.Int, typeMapper: TypeMapper): Double? = rs.getDouble(index)
    }
}
