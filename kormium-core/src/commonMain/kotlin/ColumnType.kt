package io.github.kormium

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.github.kormium.resultset.ResultSet
import io.github.kormium.sql.getBigDecimal
import io.github.kormium.sql.getJson
import io.github.kormium.sql.getUUID
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import kotlin.uuid.Uuid

/**
 * How a column's Kotlin type [T] is read from a result row and turned into a bound parameter.
 * The set of types is open: the built-ins below are ordinary [ColumnType]s, and you add your
 * own either by [convert]ing an existing one (the common case — map onto text/json/int/...) or
 * by implementing this interface directly.
 *
 * Note there is no DDL here — Kormium does not own schema (`CREATE TABLE` is raw SQL / migrations),
 * so a column type only describes value conversion, not the SQL column type.
 */
interface ColumnType<T> {
    /** Reads the value at [index] (0-based per [ResultSet]) from [rs], or null for SQL NULL. */
    fun read(rs: ResultSet, index: Int): T?

    /**
     * Converts a domain value into what gets bound as a parameter. The result still flows
     * through the backend's [TypeMapper.toParameter] and the dialect's bind rendering (so e.g.
     * a returned [JsonElement] is cast to `::jsonb` on Postgres). The default is identity.
     */
    fun toParam(value: T): Any? = value

    /**
     * A short human-readable name for this type, used only in diagnostics (e.g. result-mapping
     * errors). The default derives it from the class name (`IntColumnType` → `Int`); [convert]
     * and custom types may override for a clearer label.
     */
    val description: String get() = this::class.simpleName?.removeSuffix("ColumnType") ?: "custom"
}

/**
 * Derives a [ColumnType] for [Domain] from this one for [Stored] by mapping values both ways —
 * the lightweight path for custom types (Exposed's `transform`, Hibernate's `AttributeConverter`).
 * Storage, reading and any dialect casts are inherited; you only translate the value.
 */
fun <Domain, Stored> ColumnType<Stored>.convert(
    toStored: (Domain) -> Stored,
    fromStored: (Stored) -> Domain,
): ColumnType<Domain> = object : ColumnType<Domain> {
    override fun read(rs: ResultSet, index: Int): Domain? = this@convert.read(rs, index)?.let(fromStored)
    override fun toParam(value: Domain): Any? = this@convert.toParam(toStored(value))
    override val description: String get() = "${this@convert.description} (converted)"
}

// ---- built-in column types (the 14 Kormium ships) ----

object UuidColumnType : ColumnType<Uuid> { override fun read(rs: ResultSet, index: Int) = rs.getUUID(index) }
object BigDecimalColumnType : ColumnType<BigDecimal> { override fun read(rs: ResultSet, index: Int) = rs.getBigDecimal(index) }
object DoubleColumnType : ColumnType<Double> { override fun read(rs: ResultSet, index: Int) = rs.getDouble(index) }
object IntColumnType : ColumnType<Int> { override fun read(rs: ResultSet, index: Int) = rs.getInt(index) }
object BooleanColumnType : ColumnType<Boolean> { override fun read(rs: ResultSet, index: Int) = rs.getBoolean(index) }
object TextColumnType : ColumnType<String> { override fun read(rs: ResultSet, index: Int) = rs.getString(index) }
object InstantColumnType : ColumnType<Instant> { override fun read(rs: ResultSet, index: Int) = rs.getInstant(index) }
object JsonColumnType : ColumnType<JsonElement> { override fun read(rs: ResultSet, index: Int) = rs.getJson(index) }
object LongColumnType : ColumnType<Long> { override fun read(rs: ResultSet, index: Int) = rs.getLong(index) }
object FloatColumnType : ColumnType<Float> { override fun read(rs: ResultSet, index: Int) = rs.getFloat(index) }
object ShortColumnType : ColumnType<Short> { override fun read(rs: ResultSet, index: Int) = rs.getShort(index) }
object LocalDateColumnType : ColumnType<LocalDate> { override fun read(rs: ResultSet, index: Int) = rs.getDate(index) }
object LocalTimeColumnType : ColumnType<LocalTime> { override fun read(rs: ResultSet, index: Int) = rs.getTime(index) }
object LocalDateTimeColumnType : ColumnType<LocalDateTime> { override fun read(rs: ResultSet, index: Int) = rs.getLocalDateTime(index) }
object BytesColumnType : ColumnType<ByteArray> { override fun read(rs: ResultSet, index: Int) = rs.getBytes(index) }

// ---- ready-made custom types built on [convert] ----

/** Stores an enum by its [Enum.name] in a text column. */
inline fun <reified E : Enum<E>> enumColumnType(): ColumnType<E> {
    val byName = enumValues<E>().associateBy { it.name }
    return TextColumnType.convert(
        toStored = { e: E -> e.name },
        fromStored = { s: String -> byName[s] ?: error("Unknown ${E::class.simpleName} value: $s") },
    )
}

/** Stores a `@Serializable` value [T] as JSON (jsonb on Postgres, text on SQLite). */
inline fun <reified T> jsonColumnType(json: Json = Json.Default): ColumnType<T> {
    val ser = serializer<T>()
    return JsonColumnType.convert(
        toStored = { value: T -> json.encodeToJsonElement(ser, value) },
        fromStored = { element: JsonElement -> json.decodeFromJsonElement(ser, element) },
    )
}
