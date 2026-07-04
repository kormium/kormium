package io.github.kormium.decimal

// java.math.BigDecimal passes through PostgresJvmTypeMapper/MySqlJvmTypeMapper untouched,
// so pgjdbc/mysql-connector/r2dbc bind a typed numeric parameter (no server re-inference).
internal actual fun decimalToParam(value: Decimal): Any? = value.toJavaBigDecimal()
