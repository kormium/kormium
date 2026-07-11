package io.github.kormium.decimal

import io.github.kormium.Column
import io.github.kormium.ColumnType
import io.github.kormium.resultset.ResultSet

/**
 * Column type for exact decimal values ([Decimal]), for SQL `numeric`/`decimal` columns.
 *
 * Values travel as text on every backend — the database compares and aggregates, Kormium
 * only carries the value — except on the JVM, where parameters bind as
 * `java.math.BigDecimal` so JDBC/r2dbc drivers declare the real `numeric` parameter type.
 */
public object DecimalColumnType : ColumnType<Decimal> {
    override fun read(rs: ResultSet, index: Int): Decimal? = rs.getString(index)?.let(Decimal::parse)
    override fun toParam(value: Decimal): Any? = decimalToParam(value)
}

/** Declares a [Decimal] column: `val price by Column.decimal()`. */
public fun Column.Companion.decimal(name: String? = null): Column.Spec<Decimal> = of(DecimalColumnType, name)

internal expect fun decimalToParam(value: Decimal): Any?
