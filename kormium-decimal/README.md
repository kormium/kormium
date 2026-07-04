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
- **Non-finite values** (`NaN`, `±Infinity` — PostgreSQL `numeric` stores all three):
  `java.math.BigDecimal` cannot carry them, so on the JVM they bind as `Double` (float8,
  assignment-cast to `numeric` by the server), and they round-trip on every backend.
  On r2dbc, kormium installs its own `numeric → text` decoder (`NumericAsTextCodec`),
  because r2dbc-postgresql's built-in path goes through `java.math.BigDecimal` and throws
  on non-finite values (the same defect class as
  [pgjdbc#1941](https://github.com/pgjdbc/pgjdbc/issues/1941)).

The ORM carries values; comparison and arithmetic happen in SQL. For client-side arithmetic
`Decimal` provides `+`, `-`, `*`, `div(other, scale, roundingMode)` and friends — see the
[decimal repository](https://github.com/kormium/decimal) for the full API and benchmarks.
