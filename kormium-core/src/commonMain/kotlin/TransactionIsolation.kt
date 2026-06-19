package io.github.kormium

/**
 * A portable transaction isolation level, passed to [transaction] / [suspendTransaction].
 *
 * The four values are the SQL-standard levels. How a backend honors them differs and is
 * intentionally not hidden:
 *
 *  - **PostgreSQL** maps all four to its own levels (it has no true `READ UNCOMMITTED`, which
 *    behaves as `READ COMMITTED`).
 *  - **MySQL/MariaDB** maps all four.
 *  - **SQLite** has only one isolation level (effectively `SERIALIZABLE`); a non-null value
 *    here is *ignored* on SQLite rather than emulated. Use [readOnly][transaction] for the one
 *    knob SQLite does honor.
 *
 * Passing `null` (the default) leaves the connection's configured/default level untouched —
 * no `SET TRANSACTION` / `ISOLATION LEVEL` clause is emitted.
 *
 * [sql] is the standard SQL spelling used by the SQL-driven native backends; the JDBC and
 * r2dbc backends translate the enum through their driver APIs instead.
 */
enum class TransactionIsolation(val sql: String) {
    ReadUncommitted("READ UNCOMMITTED"),
    ReadCommitted("READ COMMITTED"),
    RepeatableRead("REPEATABLE READ"),
    Serializable("SERIALIZABLE"),
}
