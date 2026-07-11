package io.github.kormium

/**
 * SQLite dialect: standard SQL rendering (double-quoted identifiers, `:name`
 * placeholders, plain `LIMIT`/`OFFSET`). SQLite has dynamic typing with type
 * *affinity*, so non-native values (UUID, BigDecimal, JSON, temporals) are stored as
 * `TEXT` and parsed back by the result-set wrapper. Kormium does not own schema DDL, so
 * the dialect carries no column-type mapping.
 */
public object SqliteDialect : Dialect by StandardDialect {
    // SQLite has a single isolation level (effectively SERIALIZABLE), so a requested level is ignored.
    override val supportsTransactionIsolation: Boolean get() = false

    // sqlite-jdbc rejects Connection.setReadOnly after connect; PRAGMA query_only is the SQLite way.
    override val readOnlyToggle: ReadOnlyToggle =
        ReadOnlyToggle(enter = "PRAGMA query_only=ON", exit = "PRAGMA query_only=OFF")
}
