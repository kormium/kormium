package io.github.kormium

import io.github.kormium.resultset.ResultSet
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.time.toKotlinInstant
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toKotlinLocalTime
import java.sql.ResultSetMetaData
import java.time.OffsetDateTime

/**
 * Adapts a MySQL/MariaDB JDBC [java.sql.ResultSet] to core's backend-agnostic [ResultSet].
 *
 * Date/time columns are read through mysql-connector-j's `java.time` `getObject` conversions
 * (rather than text) so there is no string-parsing or timezone guesswork: `TIMESTAMP` comes back
 * as an [OffsetDateTime] (the session is pinned to UTC by the connection URL) and yields the
 * correct [Instant]. UUID (`CHAR(36)`) and `JSON` columns are plain text via [getString], which
 * lines up with korm's text-based column reading.
 */
class MySqlResultSetWrapper(private val rs: java.sql.ResultSet) : ResultSet {
    // Lazy: the hot read path maps columns positionally and never touches this.
    override val columns: Array<String> by lazy { internalGetColumns() }

    private fun internalGetColumns(): Array<String> {
        val md: ResultSetMetaData = rs.metaData
        return (0..<md.columnCount).map { md.getColumnLabel(it + 1) }.toTypedArray()
    }

    override fun next(): Boolean = rs.next()

    override fun getString(columnIndex: Int): String? = rs.getString(columnIndex + 1)

    // JDBC's primitive getters return 0/false for SQL NULL, so wasNull() must be consulted to
    // distinguish a real value from NULL on nullable columns.
    override fun getBoolean(columnIndex: Int): Boolean? = rs.getBoolean(columnIndex + 1).orNull()

    override fun getShort(columnIndex: Int): Short? = rs.getShort(columnIndex + 1).orNull()

    override fun getInt(columnIndex: Int): Int? = rs.getInt(columnIndex + 1).orNull()

    override fun getLong(columnIndex: Int): Long? = rs.getLong(columnIndex + 1).orNull()

    override fun getFloat(columnIndex: Int): Float? = rs.getFloat(columnIndex + 1).orNull()

    override fun getDouble(columnIndex: Int): Double? = rs.getDouble(columnIndex + 1).orNull()

    private fun <T> T.orNull(): T? = if (rs.wasNull()) null else this

    override fun getBytes(columnIndex: Int): ByteArray? = rs.getBytes(columnIndex + 1)

    override fun getDate(columnIndex: Int): LocalDate? =
        rs.getObject(columnIndex + 1, java.time.LocalDate::class.java)?.toKotlinLocalDate()

    override fun getTime(columnIndex: Int): LocalTime? =
        rs.getObject(columnIndex + 1, java.time.LocalTime::class.java)?.toKotlinLocalTime()

    override fun getLocalDateTime(columnIndex: Int): LocalDateTime? =
        rs.getObject(columnIndex + 1, java.time.LocalDateTime::class.java)?.toKotlinLocalDateTime()

    override fun getInstant(columnIndex: Int): Instant? =
        rs.getObject(columnIndex + 1, OffsetDateTime::class.java)?.toInstant()?.toKotlinInstant()
}
