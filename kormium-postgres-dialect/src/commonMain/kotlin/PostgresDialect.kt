package io.github.kormium

import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

/**
 * Postgres dialect: standard SQL rendering plus `::uuid` / `::jsonb` casts on UUID and
 * JSON binds, so a text-bound value is interpreted with the right type by the server.
 * The casts are explicit (not reliant on `stringtype=unspecified`), so they're correct
 * for the truly-typed r2dbc driver as well as the text-based JDBC/libpq paths.
 */
object PostgresDialect : Dialect by StandardDialect {
    override fun renderBind(name: String, value: Any?): String = when (value) {
        is Uuid -> ":$name::uuid"
        is JsonElement -> ":$name::jsonb"
        else -> StandardDialect.renderBind(name, value)
    }

    /** Transaction-scoped advisory lock; auto-released at COMMIT/ROLLBACK. */
    override fun advisoryLockSql(key: Long): String = "SELECT pg_advisory_xact_lock($key)"
}
