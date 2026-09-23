package io.github.kormium

import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

/**
 * Postgres dialect: standard SQL rendering plus `::uuid` / `::jsonb` / `::vector` casts on UUID,
 * JSON and pgvector binds, so a text-bound value is interpreted with the right type by the server.
 * The casts are explicit (not reliant on `stringtype=unspecified`), so they're correct
 * for the truly-typed r2dbc driver as well as the text-based JDBC/libpq paths.
 */
public object PostgresDialect : Dialect by StandardDialect {
    /**
     * All four lock strengths plus the contention modifier. Postgres has had `SKIP LOCKED` /
     * `NOWAIT` since 9.5 and the weaker `FOR NO KEY UPDATE` / `FOR KEY SHARE` since 9.3, so there
     * is no version gate here (unlike MySQL, where `SKIP LOCKED` needs 8.0.1).
     */
    override fun renderRowLock(lock: RowLock): String = buildString {
        append(
            when (lock.strength) {
                LockStrength.Update -> "FOR UPDATE"
                LockStrength.NoKeyUpdate -> "FOR NO KEY UPDATE"
                LockStrength.Share -> "FOR SHARE"
                LockStrength.KeyShare -> "FOR KEY SHARE"
            },
        )
        when (lock.wait) {
            LockWait.NoWait -> append(" NOWAIT")
            LockWait.SkipLocked -> append(" SKIP LOCKED")
            LockWait.Wait -> {}
        }
    }

    override fun renderBind(name: String, value: Any?): String = when (value) {
        is Uuid -> ":$name::uuid"
        is JsonElement -> ":$name::jsonb"
        // A pgvector Vector binds as its own text form (Vector.toString() -> "[1,2,3]"); the cast
        // lets the server read that text as a real vector, for the KNN distance operators.
        is Vector -> ":$name::vector"
        else -> StandardDialect.renderBind(name, value)
    }

    // The wire protocol's Bind message counts parameters in an int16, so 65535 is a hard
    // protocol ceiling, not a server setting.
    override val maxBoundParameters: Int get() = 65535

    /** Transaction-scoped advisory lock; auto-released at COMMIT/ROLLBACK. */
    override fun advisoryLockSql(key: Long): String = "SELECT pg_advisory_xact_lock($key)"
}
