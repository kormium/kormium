package io.github.kormium.postgres.resultset

import io.github.kormium.postgres.KLogger
import io.github.kormium.postgres.exception.GetColumnValueException
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.toKString
import kotlinx.datetime.*
import libpq.*
import io.github.kormium.resultset.ResultSet

private val logger = KLogger("io.github.kormium.postgres.resultset.PostgresResultSetKt")

@Suppress("TooManyFunctions")
@ExperimentalForeignApi
internal class PostgresResultSet(val internal: CPointer<PGresult>) : ResultSet {

    override val columns by lazy { internalGetColumns() }

    private fun internalGetColumns(): Array<String> {
        logger.trace { "get columns, count: ${PQnfields(internal)}" }
        return (0 until PQnfields(internal)).mapNotNull {
            PQfname(internal, it)?.toKString()
        }.toTypedArray()
    }

    /** Number of rows in the result; lets callers presize the row list they build. */
    val rowCount: Int = PQntuples(internal)

    @Suppress("UnusedPrivateProperty")
    private val columnCount: Int = PQnfields(internal)

    private var currentRow = -1


    override fun next(): Boolean {
        if (currentRow > rowCount - 2) {
            return false
        }
        currentRow++
        return true
    }

    private fun isNull(columnIndex: Int): Boolean =
        PQgetisnull(res = internal, tup_num = currentRow, field_num = columnIndex) == 1

    private fun getPointer(columnIndex: Int): CPointer<ByteVar>? {
        if (isNull(columnIndex)) return null
        return PQgetvalue(res = internal, tup_num = currentRow, field_num = columnIndex)
            ?: throw GetColumnValueException(columnIndex)
    }

    /**
     * Are all non-binary columns returned as text?
     * https://www.postgresql.org/docs/9.5/libpq-exec.html#LIBPQ-EXEC-SELECT-INFO
     *
     * No trace logging here: it sits on the per-cell hot path and the lambda would be
     * allocated on every call (the logger's trace() is not inlined), even when disabled.
     */
    override fun getString(columnIndex: Int): String? = getPointer(columnIndex)?.toKString()

    // bool/integers parse straight from libpq's null-terminated C string, skipping the
    // intermediate Kotlin String that getString()?.toX() would allocate for every cell.
    // Floats keep the String path: a correct float parser (exponents, Infinity/NaN,
    // rounding) is not worth hand-rolling, and float columns are comparatively rare.
    override fun getBoolean(columnIndex: Int): Boolean? =
        getPointer(columnIndex)?.let { it[0].toInt() == 't'.code }

    override fun getShort(columnIndex: Int): Short? =
        getPointer(columnIndex)?.let { parsePgLong(it).toShort() }

    override fun getInt(columnIndex: Int): Int? =
        getPointer(columnIndex)?.let { parsePgLong(it).toInt() }

    override fun getLong(columnIndex: Int): Long? =
        getPointer(columnIndex)?.let { parsePgLong(it) }

    override fun getFloat(columnIndex: Int): Float? = getString(columnIndex)?.toFloat()

    override fun getDouble(columnIndex: Int): Double? = getString(columnIndex)?.toDouble()

    // bytea arrives text-encoded (\x48656c6c6f or the legacy escape form), so it must be
    // DECODED. getString()?.encodeToByteArray() used to re-encode that text as UTF-8 instead,
    // returning the bytes of the encoding rather than the value it stands for.
    override fun getBytes(columnIndex: Int): ByteArray? = getPointer(columnIndex)?.let { decodePgBytea(it) }

    override fun getDate(columnIndex: Int): LocalDate? = getString(columnIndex)?.let { LocalDate.parse(it) }

    override fun getTime(columnIndex: Int): LocalTime? = getString(columnIndex)?.let { LocalTime.parse(it) }
    override fun getLocalDateTime(columnIndex: Int): LocalDateTime? =
        getString(columnIndex)?.let { parsePgLocalDateTime(it) }

    override fun getInstant(columnIndex: Int): Instant? =
        getString(columnIndex)?.let { parsePgInstant(it) }
}

/**
 * Parses a base-10 integer directly from a libpq null-terminated C string, allocating no
 * Kotlin String. Digits are accumulated as a negative number so that Long.MIN_VALUE — whose
 * magnitude cannot be represented as a positive Long — parses correctly. Postgres only feeds
 * this well-formed integer text (optional sign followed by digits).
 */
@ExperimentalForeignApi
internal fun parsePgLong(p: CPointer<ByteVar>): Long {
    var i = 0
    val negative = when (p[0].toInt()) {
        '-'.code -> { i = 1; true }
        '+'.code -> { i = 1; false }
        else -> false
    }
    var acc = 0L
    while (true) {
        val c = p[i].toInt()
        if (c == 0) break
        acc = acc * 10 - (c - '0'.code)
        i++
    }
    return if (negative) acc else -acc
}
