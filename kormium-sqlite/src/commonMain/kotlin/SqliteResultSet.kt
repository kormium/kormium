package io.github.kormium

import io.github.kormium.resultset.ResultSet
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * A materialised [ResultSet] over rows already pulled from a SQLite statement (the
 * statement is finalised before the handler runs, so values are read out eagerly).
 * Each cell holds the SQLite storage class as a Kotlin value: `Long` (INTEGER),
 * `Double` (FLOAT), `String` (TEXT), `ByteArray` (BLOB) or `null`.
 *
 * Temporals are parsed verbatim from the stored ISO-8601 text — SQLite returns exactly
 * what we wrote (`Instant.toString()` etc.), so no Postgres-style space→'T' fix-up.
 */
internal class SqliteResultSet(
    override val columns: Array<String>,
    private val rows: List<Array<Any?>>,
) : ResultSet {
    private var index = -1

    override fun next(): Boolean {
        if (index >= rows.size - 1) return false
        index++
        return true
    }

    private fun raw(columnIndex: Int): Any? = rows[index][columnIndex]

    private fun asLong(columnIndex: Int): Long? = when (val v = raw(columnIndex)) {
        null -> null
        is Long -> v
        is Double -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun asDouble(columnIndex: Int): Double? = when (val v = raw(columnIndex)) {
        null -> null
        is Double -> v
        is Long -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    override fun getString(columnIndex: Int): String? = raw(columnIndex)?.toString()

    override fun getBoolean(columnIndex: Int): Boolean? = when (val v = raw(columnIndex)) {
        null -> null
        is Long -> v != 0L
        is String -> v == "1" || v.equals("true", ignoreCase = true) || v == "t"
        else -> null
    }

    override fun getShort(columnIndex: Int): Short? = asLong(columnIndex)?.toShort()

    override fun getInt(columnIndex: Int): Int? = asLong(columnIndex)?.toInt()

    override fun getLong(columnIndex: Int): Long? = asLong(columnIndex)

    override fun getFloat(columnIndex: Int): Float? = asDouble(columnIndex)?.toFloat()

    override fun getDouble(columnIndex: Int): Double? = asDouble(columnIndex)

    override fun getBytes(columnIndex: Int): ByteArray? = when (val v = raw(columnIndex)) {
        null -> null
        is ByteArray -> v
        else -> v.toString().encodeToByteArray()
    }

    override fun getDate(columnIndex: Int): LocalDate? = getString(columnIndex)?.let { LocalDate.parse(it) }

    override fun getTime(columnIndex: Int): LocalTime? = getString(columnIndex)?.let { LocalTime.parse(it) }

    override fun getLocalDateTime(columnIndex: Int): LocalDateTime? =
        getString(columnIndex)?.let { LocalDateTime.parse(it) }

    override fun getInstant(columnIndex: Int): Instant? = getString(columnIndex)?.let { Instant.parse(it) }
}
