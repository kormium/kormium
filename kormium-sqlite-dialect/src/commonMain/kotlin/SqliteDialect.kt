package io.github.kormium

/**
 * SQLite dialect: standard SQL rendering (double-quoted identifiers, `:name` placeholders).
 * SQLite has dynamic typing with type *affinity*, so non-native values (UUID, BigDecimal,
 * JSON, temporals) are stored as `TEXT` and parsed back by the result-set wrapper. Kormium
 * does not own schema DDL, so the dialect carries no column-type mapping.
 *
 * Like MySQL, SQLite rejects a bare `OFFSET` — see [renderLimitOffset].
 */
public object SqliteDialect : Dialect by StandardDialect {
    /**
     * SQLite's grammar only allows `OFFSET` as part of a `LIMIT` clause: `SELECT ... OFFSET 2`
     * is a syntax error ("near OFFSET"). When only an offset is set, emit SQLite's documented
     * "no limit" sentinel `LIMIT -1` to carry it. When a real limit is set, the standard
     * `LIMIT n OFFSET m` is valid as-is.
     */
    override fun renderLimitOffset(limit: UInt, offset: UInt): String {
        val limited = limit != UInt.MAX_VALUE
        val offsetted = offset != 0u
        return when {
            !limited && !offsetted -> ""
            !offsetted -> "LIMIT $limit "
            limited -> "LIMIT $limit OFFSET $offset "
            else -> "LIMIT -1 OFFSET $offset "
        }
    }

    // SQLite has a single isolation level (effectively SERIALIZABLE), so a requested level is ignored.
    override val supportsTransactionIsolation: Boolean get() = false

    // SQLITE_MAX_VARIABLE_NUMBER, which is a *build* option, not an engine version: 32766 is the
    // default since SQLite 3.32 (999 before it), while sqlite-jdbc compiles its bundled library
    // with 250000 (measured: it rejects the 250002nd parameter). One dialect object serves every
    // SQLite driver — sqlite-jdbc, androidx.sqlite-bundled, and a cinterop build against whatever
    // system libsqlite3 the target has — so take the floor of the modern default rather than the
    // ceiling any one of them happens to allow.
    override val maxBoundParameters: Int get() = 32766

    // sqlite-jdbc rejects Connection.setReadOnly after connect; PRAGMA query_only is the SQLite way.
    override val readOnlyToggle: ReadOnlyToggle =
        ReadOnlyToggle(enter = "PRAGMA query_only=ON", exit = "PRAGMA query_only=OFF")
}
