package io.github.kormium

import kotlin.jvm.JvmName

// Aggregates are Operand (appear in SELECT, are read from a ResultRow, and compare to a typed literal
// like any operand — `total gt 100`), and since Operand is an Expression they also work in `having(...)`.

/** `COUNT(*)` — the number of rows in the group. */
public fun count(): Operand<Long> = object : Operand<Long> {
    override val columnType = LongColumnType
    override fun toSql(builder: ParamBuilder) = "COUNT(*)"
    override fun resultKey() = "COUNT(*)"
}

/** `COUNT(column)` — the non-null values of the column in the group. */
public fun Column<*, *, *>.count(): Operand<Long> {
    val column = this
    return object : Operand<Long> {
        override val columnType = LongColumnType
        override fun toSql(builder: ParamBuilder) = "COUNT(${column.toSql(builder)})"
        override fun resultKey() = "COUNT(${column.resultKey()})"
    }
}

// MIN/MAX keep the column's type (read through its type mapping) — MIN/MAX of an integer is
// still that integer. SUM is different: SUM over an integer column returns a wider type
// server-side (Postgres: bigint), so reading it back through the Int mapping would overflow
// (toInt() throws). The integer-family sum() overloads below therefore return Operand<Long>
// and read the aggregate as a Long; SUM of a Decimal/Double column keeps the column type
// via the generic sum().
public fun <Z> Column<Z, *, *>.min(): Operand<Z> = ColumnAggregate("MIN", this)
public fun <Z> Column<Z, *, *>.max(): Operand<Z> = ColumnAggregate("MAX", this)

/** `SUM(column)` for a non-integer column (e.g. Decimal/Double), read through its type. */
public fun <Z> Column<Z, *, *>.sum(): Operand<Z> = ColumnAggregate("SUM", this)

// More-specific sum() overloads for integer columns: they win overload resolution over the
// generic sum() above and return Operand<Long>, reading the (bigint) aggregate as a Long so
// sums beyond Int.MAX_VALUE don't overflow. @JvmName disambiguates the otherwise-identical JVM
// signatures (generic erasure collides with these).
@JvmName("sumInt")
public fun Column<kotlin.Int, *, *>.sum(): Operand<Long> = LongAggregate(this)

@JvmName("sumShort")
public fun Column<kotlin.Short, *, *>.sum(): Operand<Long> = LongAggregate(this)

@JvmName("sumLong")
public fun Column<kotlin.Long, *, *>.sum(): Operand<Long> = LongAggregate(this)

private class ColumnAggregate<Z>(private val fn: String, private val column: Column<Z, *, *>) : Operand<Z> {
    override val columnType: ColumnType<Z> = column.columnType
    override fun toSql(builder: ParamBuilder) = "$fn(${column.toSql(builder)})"
    override fun resultKey() = "$fn(${column.resultKey()})"
}

// SUM(column) read as a Long (the server-side bigint width), regardless of the column's own type.
private class LongAggregate(private val column: Column<*, *, *>) : Operand<Long> {
    override val columnType = LongColumnType
    override fun toSql(builder: ParamBuilder) = "SUM(${column.toSql(builder)})"
    override fun resultKey() = "SUM(${column.resultKey()})"
}

/** `AVG(column)` as a Double. */
public fun Column<*, *, *>.avg(): Operand<Double> {
    val column = this
    return object : Operand<Double> {
        override val columnType = DoubleColumnType
        override fun toSql(builder: ParamBuilder) = "AVG(${column.toSql(builder)})"
        override fun resultKey() = "AVG(${column.resultKey()})"
    }
}
