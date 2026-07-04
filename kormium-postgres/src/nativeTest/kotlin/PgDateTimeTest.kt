import io.github.kormium.postgres.resultset.parsePgInstant
import io.github.kormium.postgres.resultset.parsePgLocalDateTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure parsing tests for Postgres' ISO date/time text output — no database needed.
 * Regression for getInstant throwing on the common UTC form `...+00`, which the old
 * `replaceRange(10, 11, "T")` + `Instant.parse` path could not handle.
 */
class PgDateTimeTest {

    @Test
    fun utcOffsetHoursOnly() {
        // Postgres renders a UTC timestamptz with an hours-only offset.
        assertEquals(Instant.parse("2024-01-15T13:45:30Z"), parsePgInstant("2024-01-15 13:45:30+00"))
    }

    @Test
    fun fractionalSecondsAndHoursOnlyOffset() {
        assertEquals(
            Instant.parse("2024-01-15T13:45:30.123456Z"),
            parsePgInstant("2024-01-15 13:45:30.123456+00"),
        )
    }

    @Test
    fun positiveOffsetWithMinutes() {
        // +05:30 means the instant is 5h30 earlier in UTC.
        assertEquals(Instant.parse("2024-01-15T08:15:30Z"), parsePgInstant("2024-01-15 13:45:30+05:30"))
    }

    @Test
    fun negativeOffset() {
        assertEquals(Instant.parse("2024-01-15T21:45:30Z"), parsePgInstant("2024-01-15 13:45:30-08"))
    }

    @Test
    fun offsetWithSeconds() {
        // Rare but legal historical offsets carry seconds.
        assertEquals(
            Instant.parse("2024-01-15T13:45:30Z"),
            parsePgInstant("2024-01-15 13:46:00+00:00:30"),
        )
    }

    @Test
    fun localDateTimeSeparator() {
        assertEquals(
            LocalDateTime.parse("2024-01-15T13:45:30"),
            parsePgLocalDateTime("2024-01-15 13:45:30"),
        )
    }

    @Test
    fun localDateTimeWithFraction() {
        assertEquals(
            LocalDateTime.parse("2024-01-15T13:45:30.123"),
            parsePgLocalDateTime("2024-01-15 13:45:30.123"),
        )
    }
}
