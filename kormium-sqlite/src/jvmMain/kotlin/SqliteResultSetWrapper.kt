package io.github.kormium

import io.github.kormium.resultset.ResultSet
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import java.sql.ResultSetMetaData

/**
 * Wraps a JDBC [java.sql.ResultSet] from sqlite-jdbc as core's [ResultSet].
 *
 * Unlike Postgres, SQLite stores temporal values as the verbatim text we wrote
 * (`Instant.toString()` etc., already ISO-8601 with a 'T'), so dates/times are parsed
 * as-is — no space→'T' fix-up.
 */
class SqliteResultSetWrapper(private val rs: java.sql.ResultSet) : ResultSet {
    // Lazy: the hot read path maps columns positionally and never touches this.
    override val columns: Array<String> by lazy { internalGetColumns() }

    private fun internalGetColumns(): Array<String> {
        val md: ResultSetMetaData = rs.metaData
        val numCols = md.columnCount
        return (0..<numCols).map { md.getColumnName(it + 1) }.toTypedArray()
    }

    override fun next(): Boolean = rs.next()

    override fun getString(columnIndex: Int): String? = rs.getString(columnIndex + 1)

    // JDBC's primitive getters return 0/false for SQL NULL, so wasNull() must be
    // consulted to distinguish a real value from NULL on nullable columns.
    override fun getBoolean(columnIndex: Int): Boolean? = rs.getBoolean(columnIndex + 1).orNull()

    override fun getShort(columnIndex: Int): Short? = rs.getShort(columnIndex + 1).orNull()

    override fun getInt(columnIndex: Int): Int? = rs.getInt(columnIndex + 1).orNull()

    override fun getLong(columnIndex: Int): Long? = rs.getLong(columnIndex + 1).orNull()

    override fun getFloat(columnIndex: Int): Float? = rs.getFloat(columnIndex + 1).orNull()

    override fun getDouble(columnIndex: Int): Double? = rs.getDouble(columnIndex + 1).orNull()

    private fun <T> T.orNull(): T? = if (rs.wasNull()) null else this

    override fun getBytes(columnIndex: Int): ByteArray? = rs.getBytes(columnIndex + 1)

    override fun getDate(columnIndex: Int): LocalDate? =
        rs.getString(columnIndex + 1)?.let { LocalDate.parse(it) }

    override fun getTime(columnIndex: Int): LocalTime? =
        rs.getString(columnIndex + 1)?.let { LocalTime.parse(it) }

    override fun getLocalDateTime(columnIndex: Int): LocalDateTime? =
        rs.getString(columnIndex + 1)?.let { LocalDateTime.parse(it) }

    override fun getInstant(columnIndex: Int): Instant? =
        rs.getString(columnIndex + 1)?.let { Instant.parse(it) }
}
