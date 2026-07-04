package io.github.kormium.postgres.resultset

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant

/**
 * Parsers for Postgres' ISO-style date/time text output.
 *
 * Postgres prints a timestamp as `2024-01-15 13:45:30[.ffffff]` and a timestamptz as the
 * same followed by a zone offset rendered with the *fewest* fields needed: `+00`, `-08`,
 * `+05:30`, even `+05:30:45`. kotlinx-datetime's parsers, by contrast, require an ISO-8601
 * `T` between date and time and a full `±HH:MM` offset — so `Instant.parse("…+00")` throws.
 * These helpers normalise both quirks before delegating to the kotlinx parsers.
 */

/** Replaces Postgres' space date/time separator with the ISO `T`. */
private fun String.isoSeparator(): String {
    val space = indexOf(' ')
    return if (space < 0) this else replaceRange(space, space + 1, "T")
}

/** Parses a Postgres `timestamp` (no zone) into a [LocalDateTime]. */
internal fun parsePgLocalDateTime(raw: String): LocalDateTime = LocalDateTime.parse(raw.isoSeparator())

/** Parses a Postgres `timestamptz` into an [Instant], handling the short offset forms. */
internal fun parsePgInstant(raw: String): Instant {
    val s = raw.isoSeparator()
    // The offset begins at the first '+'/'-'/'Z' that follows the time component; start the
    // search after the 'T' so the date's own dashes are not mistaken for an offset sign.
    val timeStart = s.indexOf('T') + 1
    var i = timeStart
    var offsetStart = -1
    while (i < s.length) {
        val c = s[i]
        if (c == '+' || c == '-' || c == 'Z') {
            offsetStart = i
            break
        }
        i++
    }
    // A timestamptz always carries an offset; if one is somehow absent, read it as UTC.
    if (offsetStart < 0) return parsePgLocalDateTime(raw).toInstant(UtcOffset.ZERO)
    val local = LocalDateTime.parse(s.substring(0, offsetStart))
    return local.toInstant(parsePgOffset(s.substring(offsetStart)))
}

/** Parses an offset in any of Postgres' forms: `Z`, `±HH`, `±HH:MM`, `±HH:MM:SS`. */
private fun parsePgOffset(text: String): UtcOffset {
    if (text == "Z") return UtcOffset.ZERO
    val sign = if (text[0] == '-') -1 else 1
    val parts = text.substring(1).split(':')
    return UtcOffset(
        hours = parts[0].toInt() * sign,
        minutes = (parts.getOrNull(1)?.toInt() ?: 0) * sign,
        seconds = (parts.getOrNull(2)?.toInt() ?: 0) * sign,
    )
}
