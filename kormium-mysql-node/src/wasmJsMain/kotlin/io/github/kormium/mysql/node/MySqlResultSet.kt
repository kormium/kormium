package io.github.kormium.mysql.node

import io.github.kormium.resultset.ResultSet
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Adapts one mysql2 result row (a positional JS array) to korm's [ResultSet]. One per row, so
 * [next] is always false. Values are read through [cellText] and parsed from text. Indexes 0-based.
 */
internal class MySqlResultSet(
    private val row: JsArray<JsAny?>,
    override val columns: Array<String>,
) : ResultSet {

    override fun next(): Boolean = false

    private fun text(columnIndex: Int): String? = cellText(row[columnIndex])

    override fun getString(columnIndex: Int): String? = text(columnIndex)

    override fun getBoolean(columnIndex: Int): Boolean? =
        text(columnIndex)?.let { it == "true" || it == "t" || it == "1" }

    override fun getShort(columnIndex: Int): Short? = text(columnIndex)?.toShort()

    override fun getInt(columnIndex: Int): Int? = text(columnIndex)?.toInt()

    override fun getLong(columnIndex: Int): Long? = text(columnIndex)?.toLong()

    override fun getFloat(columnIndex: Int): Float? = text(columnIndex)?.toFloat()

    override fun getDouble(columnIndex: Int): Double? = text(columnIndex)?.toDouble()

    override fun getBytes(columnIndex: Int): ByteArray? =
        throw UnsupportedOperationException("kormium-mysql-node does not support blob reads yet")

    override fun getDate(columnIndex: Int): LocalDate? =
        text(columnIndex)?.let { LocalDate.parse(it.substringBefore('T')) }

    override fun getTime(columnIndex: Int): LocalTime? =
        text(columnIndex)?.let { LocalTime.parse(it) }

    override fun getLocalDateTime(columnIndex: Int): LocalDateTime? =
        text(columnIndex)?.let { LocalDateTime.parse(it.removeSuffix("Z")) }

    override fun getInstant(columnIndex: Int): Instant? =
        text(columnIndex)?.let { Instant.parse(it) }
}
