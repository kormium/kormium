package io.github.kormium.mysql

import io.github.kormium.resultset.ResultSet
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * A core [ResultSet] over a fully-materialized MySQL result. Each cell is the column's raw bytes
 * (or `null` for SQL NULL): the native driver binds every output column as `MYSQL_TYPE_STRING`, so
 * the bytes are the value's text form — exactly what Kormium's text-based column reading expects (the
 * same model as libpq's text result format). Binary columns are read verbatim via [getBytes].
 *
 * Date/times come back as MySQL's space-separated `"yyyy-MM-dd HH:mm:ss"`; the session is pinned to
 * UTC on connect, so a `TIMESTAMP` text reads back as the correct [Instant].
 */
internal class MySqlResultSet(
    override val columns: Array<String>,
    private val rows: List<Array<ByteArray?>>,
) : ResultSet {

    private var index = -1

    override fun next(): Boolean {
        index++
        return index < rows.size
    }

    private fun text(columnIndex: Int): String? = rows[index][columnIndex]?.decodeToString()

    override fun getString(columnIndex: Int): String? = text(columnIndex)

    // MySQL renders BOOLEAN/TINYINT(1) as "0"/"1".
    override fun getBoolean(columnIndex: Int): Boolean? = text(columnIndex)?.let { it != "0" }

    override fun getShort(columnIndex: Int): Short? = text(columnIndex)?.toShort()

    override fun getInt(columnIndex: Int): Int? = text(columnIndex)?.toInt()

    override fun getLong(columnIndex: Int): Long? = text(columnIndex)?.toLong()

    override fun getFloat(columnIndex: Int): Float? = text(columnIndex)?.toFloat()

    override fun getDouble(columnIndex: Int): Double? = text(columnIndex)?.toDouble()

    override fun getBytes(columnIndex: Int): ByteArray? = rows[index][columnIndex]

    override fun getDate(columnIndex: Int): LocalDate? = text(columnIndex)?.let { LocalDate.parse(it) }

    override fun getTime(columnIndex: Int): LocalTime? = text(columnIndex)?.let { LocalTime.parse(it) }

    override fun getLocalDateTime(columnIndex: Int): LocalDateTime? =
        text(columnIndex)?.let { LocalDateTime.parse(it.replace(' ', 'T')) }

    override fun getInstant(columnIndex: Int): Instant? =
        text(columnIndex)?.let { LocalDateTime.parse(it.replace(' ', 'T')).toInstant(TimeZone.UTC) }
}
