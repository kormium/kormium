package io.github.kormium.wasm.driver

import io.github.kormium.resultset.ResultSet
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * A [ResultSet] over one positional JS result row, reading every value as text (via [cellText]) and
 * parsing it. Shared by all Wasm engines: their drivers return JS-native values, and korm's
 * non-native types (UUID/BigDecimal/temporals) are stored/sent as text anyway, so one text path
 * covers them all. Each driver hands the engine a full row, so [next] is always false; column
 * indexes are 0-based.
 */
public class TextResultSet(
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
        throw UnsupportedOperationException("kormium Wasm engines do not support blob reads yet")

    override fun getDate(columnIndex: Int): LocalDate? =
        text(columnIndex)?.let { LocalDate.parse(it.substringBefore('T')) }

    override fun getTime(columnIndex: Int): LocalTime? =
        text(columnIndex)?.let { LocalTime.parse(it) }

    override fun getLocalDateTime(columnIndex: Int): LocalDateTime? =
        text(columnIndex)?.let { LocalDateTime.parse(it.removeSuffix("Z")) }

    override fun getInstant(columnIndex: Int): Instant? =
        text(columnIndex)?.let { Instant.parse(it) }
}
