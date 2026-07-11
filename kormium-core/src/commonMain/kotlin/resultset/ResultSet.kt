package io.github.kormium.resultset

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Backend-agnostic view of a query result. A driver wraps its native result set
 * (JDBC, libpq, sqlite3, r2dbc) in this interface; core reads values through it.
 *
 * **Column indexes are 0-based** — the first selected column is 0 (unlike JDBC's
 * 1-based convention; JDBC-backed wrappers translate internally). Every getter
 * returns `null` for SQL `NULL`.
 *
 * The cursor starts before the first row; call [next] to advance.
 */
@Suppress("TooManyFunctions")
public interface ResultSet {

    /** The selected column names, in select-list order. */
    public val columns: Array<String>

    /**
     * Moves the cursor forward one row. Returns `true` if the new current row is
     * valid, `false` when there are no more rows.
     */
    public fun next(): Boolean

    /** Reads column [columnIndex] (0-based) as a [String], or `null` for SQL `NULL`. */
    public fun getString(columnIndex: Int): String?

    /** Reads column [columnIndex] (0-based) as a [Boolean], or `null` for SQL `NULL`. */
    public fun getBoolean(columnIndex: Int): Boolean?

    /** Reads column [columnIndex] (0-based) as a [Short], or `null` for SQL `NULL`. */
    public fun getShort(columnIndex: Int): Short?

    /** Reads column [columnIndex] (0-based) as an [Int], or `null` for SQL `NULL`. */
    public fun getInt(columnIndex: Int): Int?

    /** Reads column [columnIndex] (0-based) as a [Long], or `null` for SQL `NULL`. */
    public fun getLong(columnIndex: Int): Long?

    /** Reads column [columnIndex] (0-based) as a [Float], or `null` for SQL `NULL`. */
    public fun getFloat(columnIndex: Int): Float?

    /** Reads column [columnIndex] (0-based) as a [Double], or `null` for SQL `NULL`. */
    public fun getDouble(columnIndex: Int): Double?

    /** Reads column [columnIndex] (0-based) as the driver's raw bytes, or `null` for SQL `NULL`. */
    public fun getBytes(columnIndex: Int): ByteArray?

    /** Reads column [columnIndex] (0-based) as a [LocalDate], or `null` for SQL `NULL`. */
    public fun getDate(columnIndex: Int): LocalDate?

    /** Reads column [columnIndex] (0-based) as a [LocalTime], or `null` for SQL `NULL`. */
    public fun getTime(columnIndex: Int): LocalTime?

    /** Reads column [columnIndex] (0-based) as a [LocalDateTime], or `null` for SQL `NULL`. */
    public fun getLocalDateTime(columnIndex: Int): LocalDateTime?

    /** Reads column [columnIndex] (0-based) as an [Instant], or `null` for SQL `NULL`. */
    public fun getInstant(columnIndex: Int): Instant?
}
