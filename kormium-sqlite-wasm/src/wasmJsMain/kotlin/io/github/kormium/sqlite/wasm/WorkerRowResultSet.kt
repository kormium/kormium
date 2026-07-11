package io.github.kormium.sqlite.wasm

import io.github.kormium.resultset.ResultSet
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * A [ResultSet] over one already-decoded row — a [WorkerConnection.query] result row, whose cells
 * are plain Kotlin values ([String]/[Long]/[Double]/[ByteArray]/`null`) via
 * `decodeSqliteWasmParams`. Temporal types are stored/rendered as ISO text by
 * [io.github.kormium.SqliteDialect], same convention as every other Wasm engine (see
 * `io.github.kormium.wasm.driver.TextResultSet`). Each driver hands one row, so [next] is always
 * `false`; column indexes are 0-based.
 */
internal class WorkerRowResultSet(private val row: List<Any?>, override val columns: Array<String>) : ResultSet {

    override fun next(): Boolean = false

    private fun raw(columnIndex: Int): Any? = row[columnIndex]
    private fun text(columnIndex: Int): String? = raw(columnIndex)?.toString()

    override fun getString(columnIndex: Int): String? = text(columnIndex)

    override fun getBoolean(columnIndex: Int): Boolean? =
        text(columnIndex)?.let { it == "true" || it == "t" || it == "1" }

    override fun getShort(columnIndex: Int): Short? = getLong(columnIndex)?.toShort()

    override fun getInt(columnIndex: Int): Int? = getLong(columnIndex)?.toInt()

    override fun getLong(columnIndex: Int): Long? = when (val value = raw(columnIndex)) {
        null -> null
        is Long -> value
        is Double -> value.toLong()
        else -> value.toString().toLong()
    }

    override fun getFloat(columnIndex: Int): Float? = getDouble(columnIndex)?.toFloat()

    override fun getDouble(columnIndex: Int): Double? = when (val value = raw(columnIndex)) {
        null -> null
        is Long -> value.toDouble()
        is Double -> value
        else -> value.toString().toDouble()
    }

    override fun getBytes(columnIndex: Int): ByteArray? = raw(columnIndex) as? ByteArray

    override fun getDate(columnIndex: Int): LocalDate? =
        text(columnIndex)?.let { LocalDate.parse(it.substringBefore('T')) }

    override fun getTime(columnIndex: Int): LocalTime? =
        text(columnIndex)?.let { LocalTime.parse(it) }

    override fun getLocalDateTime(columnIndex: Int): LocalDateTime? =
        text(columnIndex)?.let { LocalDateTime.parse(it.removeSuffix("Z")) }

    override fun getInstant(columnIndex: Int): Instant? =
        text(columnIndex)?.let { Instant.parse(it) }
}
