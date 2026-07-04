package io.github.kormium

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.time.toJavaInstant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toJavaLocalTime
import kotlinx.serialization.json.JsonElement
import java.time.ZoneOffset
import kotlin.uuid.Uuid

/**
 * JVM MySQL/MariaDB parameter mapping: binds values as properly-typed JDBC objects (mirrors
 * [PostgresJvmTypeMapper]) so server-prepared statements declare real parameter types.
 *
 * MySQL has no native UUID or cast syntax, so a [Uuid] binds as its `CHAR(36)` text form, and a
 * [JsonElement] binds as a string literal — MySQL parses it into a `JSON` column. An [Instant]
 * binds as an `OffsetDateTime` at UTC (a `TIMESTAMP` is stored/compared in UTC); the connection
 * URL pins the session zone to UTC so the value round-trips unchanged.
 */
object MySqlJvmTypeMapper : TypeMapper {
    override fun toParameter(value: Any?): Any? = when (value) {
        // StandardTypeMapper would toString() these; pass them through so they bind as real types.
        is Float, is Short -> value
        is Uuid -> value.toString()
        // kormium-decimal's toParam already yields java.math.BigDecimal on the JVM; pass it
        // through so the connector binds a typed DECIMAL parameter.
        is java.math.BigDecimal -> value
        is Instant -> value.toJavaInstant().atOffset(ZoneOffset.UTC)
        is LocalDate -> value.toJavaLocalDate()
        is LocalTime -> value.toJavaLocalTime()
        is LocalDateTime -> value.toJavaLocalDateTime()
        is JsonElement -> value.toString()
        else -> StandardTypeMapper.toParameter(value)
    }
}
