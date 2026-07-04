package io.github.kormium.r2dbc

import io.github.kormium.resultset.ResultSet
import io.r2dbc.postgresql.codec.Json
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.time.toKotlinInstant
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toKotlinLocalTime

/**
 * Adapts one r2dbc [Row] to korm's [ResultSet]. r2dbc already hands the mapping
 * function a positioned row per result row, so [next] is never used by korm's per-row
 * handler — the cursor model collapses to a single row. Column indexes are 0-based, as
 * in r2dbc and in korm's positional read path.
 *
 * The text types (UUID/BigDecimal/Json) are read through [getString] (korm parses them
 * from text), so reading the raw value and toString()'ing it lines up with the same
 * text mapping the JDBC/libpq backends use.
 */
internal class R2dbcResultSet(
    private val row: Row,
    private val metadata: RowMetadata,
) : ResultSet {

    override val columns: Array<String> by lazy {
        metadata.columnMetadatas.map { it.name }.toTypedArray()
    }

    override fun next(): Boolean = false

    override fun getString(columnIndex: Int): String? = when (val v = row.get(columnIndex)) {
        null -> null
        // json/jsonb come back as r2dbc-postgresql's Json wrapper; its toString() isn't the raw
        // text korm's getJson()/text mapping expects, so unwrap it explicitly.
        is Json -> v.asString()
        else -> v.toString()
    }

    override fun getBoolean(columnIndex: Int): Boolean? = row.get(columnIndex, Boolean::class.javaObjectType)

    override fun getShort(columnIndex: Int): Short? = row.get(columnIndex, Short::class.javaObjectType)

    override fun getInt(columnIndex: Int): Int? = row.get(columnIndex, Int::class.javaObjectType)

    override fun getLong(columnIndex: Int): Long? = row.get(columnIndex, Long::class.javaObjectType)

    override fun getFloat(columnIndex: Int): Float? = row.get(columnIndex, Float::class.javaObjectType)

    override fun getDouble(columnIndex: Int): Double? = row.get(columnIndex, Double::class.javaObjectType)

    override fun getBytes(columnIndex: Int): ByteArray? = row.get(columnIndex, ByteArray::class.java)

    override fun getDate(columnIndex: Int): LocalDate? =
        row.get(columnIndex, java.time.LocalDate::class.java)?.toKotlinLocalDate()

    override fun getTime(columnIndex: Int): LocalTime? =
        row.get(columnIndex, java.time.LocalTime::class.java)?.toKotlinLocalTime()

    override fun getLocalDateTime(columnIndex: Int): LocalDateTime? =
        row.get(columnIndex, java.time.LocalDateTime::class.java)?.toKotlinLocalDateTime()

    override fun getInstant(columnIndex: Int): Instant? =
        row.get(columnIndex, java.time.OffsetDateTime::class.java)?.toInstant()?.toKotlinInstant()
}
