package io.github.kormium.decimal

// java.math.BigDecimal passes through PostgresJvmTypeMapper/MySqlJvmTypeMapper untouched,
// so pgjdbc/mysql-connector/r2dbc bind a typed numeric parameter (no server re-inference).
//
// java.math has no non-finite values, so NaN/±Infinity (PostgreSQL `numeric` stores all
// three) travel as a Double instead: the driver binds float8, which the server
// assignment-casts to numeric — the read side already parses them back into Decimal.
internal actual fun decimalToParam(value: Decimal): Any? =
    if (value.isFinite) value.toJavaBigDecimal() else value.toDouble()
