package io.github.kormium

/**
 * MySQL / MariaDB dialect. Differs from [StandardDialect] in three places:
 *
 *  - identifiers are quoted with backticks (MySQL's default), not double quotes;
 *  - `LIMIT`/`OFFSET` are rendered together, because MySQL rejects a bare `OFFSET` — see
 *    [renderLimitOffset];
 *  - there is no transaction-scoped advisory lock (MySQL `GET_LOCK` is session-scoped and is
 *    not released at `COMMIT`), so [advisoryLockSql] stays `null` like SQLite.
 *
 * Bind rendering is the plain `:name` form: MySQL has no `::uuid` / `::jsonb` cast syntax. A
 * [Uuid] is stored as text (`CHAR(36)`) and a [kotlinx.serialization.json.JsonElement] binds as a
 * string literal into a `JSON` column — both handled by `MySqlJvmTypeMapper`, not the dialect.
 */
object MySqlDialect : Dialect by StandardDialect {
    override fun quoteIdentifier(name: String): String =
        "`${name.replace("`", "``")}`"

    /**
     * MySQL needs a `LIMIT` whenever an `OFFSET` is present — `SELECT ... OFFSET 5` is a syntax
     * error. So when only an offset is set we emit a sentinel `LIMIT` of the maximum unsigned
     * BIGINT (MySQL's documented "all rows from here" idiom). When a real limit is set, the
     * standard `LIMIT n OFFSET m` is valid as-is.
     */
    override fun renderLimitOffset(limit: UInt, offset: UInt): String {
        val limited = limit != UInt.MAX_VALUE
        val offsetted = offset != 0u
        return when {
            !limited && !offsetted -> ""
            !offsetted -> "LIMIT $limit "
            limited -> "LIMIT $limit OFFSET $offset "
            // Offset without a real limit: MySQL requires a LIMIT, so use the max-BIGINT sentinel.
            else -> "LIMIT 18446744073709551615 OFFSET $offset "
        }
    }

    // MySQL has no RETURNING — the core re-selects the inserted row by primary key instead.
    override val supportsReturning: Boolean get() = false

    // MySQL spells "all defaults" as `() VALUES ()`, not `DEFAULT VALUES`.
    override fun renderInsertDefaultValues(qualifiedTable: String): String =
        "INSERT INTO $qualifiedTable () VALUES ()"

    // MySQL upsert keys on any unique/primary index, so the conflict column list is not used.
    override fun renderUpsertSuffix(conflictColumns: List<String>, setClause: String): String =
        "ON DUPLICATE KEY UPDATE $setClause"

    // No `ON CONFLICT DO NOTHING`; a no-op self-assignment on a conflict column ignores a dup key.
    override fun renderInsertOrIgnoreSuffix(conflictColumns: List<String>): String =
        "ON DUPLICATE KEY UPDATE ${conflictColumns.first()} = ${conflictColumns.first()}"
}
