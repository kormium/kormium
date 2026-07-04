# kormium-decimal

Exact decimal columns for Kormium, backed by
[`io.github.kormium:decimal`](https://github.com/kormium/decimal) — a standalone
Kotlin Multiplatform decimal library.

Kormium-core deliberately ships no type implementations, so decimal support lives here,
bridged through the open `ColumnType` seam.

```kotlin
implementation("io.github.kormium:kormium-decimal")
```

```kotlin
import io.github.kormium.decimal.Decimal
import io.github.kormium.decimal.decimal

object Orders : Table<App, Order>("orders", ::Order) {
    val id    by Column.UUID().primaryKey()
    val total by Column.decimal()          // entity property: Decimal
}

Orders.find { where { Orders.total gtEq Decimal.parse("99.90") } }
```

How values travel:

- **Read**: the driver returns the column as text; `DecimalColumnType` parses it with
  `Decimal.parse` (handles plain and scientific notation, `NaN`, `Infinity`).
- **Bind, JVM/Android**: parameters bind as `java.math.BigDecimal`, so JDBC and r2dbc
  drivers declare a real `numeric`/`DECIMAL` parameter type (no server re-inference).
- **Bind, everywhere else** (Native, JS, Wasm, Node engines): parameters travel as decimal
  text, which every backend accepts for `numeric` columns.

The ORM carries values; comparison and arithmetic happen in SQL. For client-side arithmetic
`Decimal` provides `+`, `-`, `*`, `div(other, scale, roundingMode)` and friends — see the
[decimal repository](https://github.com/kormium/decimal) for the full API and benchmarks.
