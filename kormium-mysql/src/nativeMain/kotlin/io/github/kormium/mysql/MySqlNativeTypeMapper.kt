package io.github.kormium.mysql

import io.github.kormium.StandardTypeMapper
import io.github.kormium.TypeMapper
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Native MySQL parameter mapping. The native driver binds every value as text (MySQL coerces the
 * string to the column type), so this only needs to fix the cases where the default text form is
 * not a valid MySQL literal:
 *
 *  - an [Instant] / [LocalDateTime] render with a `T` separator (and `Z`), which MySQL rejects —
 *    they become `"yyyy-MM-dd HH:mm:ss"` (the Instant first projected to UTC, matching the
 *    `SET time_zone = '+00:00'` the driver issues on connect).
 *
 * Everything else (UUID, JsonElement, Decimal, primitives, LocalDate/LocalTime) is handled by
 * [StandardTypeMapper] — their `toString()` is already a valid MySQL literal.
 */
public object MySqlNativeTypeMapper : TypeMapper {
    override fun toParameter(value: Any?): Any? = when (value) {
        is Instant -> value.toLocalDateTime(TimeZone.UTC).toMysqlText()
        is LocalDateTime -> value.toMysqlText()
        // MySQL's BOOLEAN is TINYINT: a text-bound parameter must be "1"/"0", not "true"/"false"
        // (which strict mode rejects with "Incorrect integer value").
        is Boolean -> if (value) "1" else "0"
        else -> StandardTypeMapper.toParameter(value)
    }

    private fun LocalDateTime.toMysqlText(): String = toString().replace('T', ' ')
}
