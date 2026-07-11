package io.github.kormium.r2dbc

import io.github.kormium.StandardTypeMapper
import io.github.kormium.TypeMapper
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toJavaLocalTime

/**
 * Postgres r2dbc parameter mapping: like [io.github.kormium.PostgresJvmTypeMapper] it binds
 * values as properly-typed objects (r2dbc-postgresql declares the real parameter type), but
 * only for the types r2dbc-postgresql has codecs for — pgjdbc-specific carriers like
 * `PGobject` must not appear here. Uuid and JsonElement stay text: [io.github.kormium.postgres.PostgresDialect]
 * renders `::uuid` / `::jsonb` casts for them, which r2dbc-postgresql handles as-is.
 */
internal object PostgresR2dbcTypeMapper : TypeMapper {
    override fun toParameter(value: Any?): Any? = when (value) {
        is Float, is Short -> value
        // kormium-decimal's toParam yields java.math.BigDecimal on the JVM; pass it through
        // so the driver binds a typed numeric parameter (no ::numeric cast exists for it).
        is java.math.BigDecimal -> value
        // Same instant as OffsetDateTime at UTC — r2dbc-postgresql binds it as timestamptz
        // (the same shape MySqlJvmTypeMapper already uses on r2dbc-mysql).
        is Instant -> value.toJavaInstant().atOffset(ZoneOffset.UTC)
        is LocalDate -> value.toJavaLocalDate()
        is LocalTime -> value.toJavaLocalTime()
        is LocalDateTime -> value.toJavaLocalDateTime()
        else -> StandardTypeMapper.toParameter(value)
    }
}
