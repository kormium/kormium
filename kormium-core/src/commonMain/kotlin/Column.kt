package io.github.kormium

import io.github.kormium.resultset.ResultSet
import kotlinx.serialization.json.JsonElement
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

private val logger = kormiumLogger()

/**
 * A typed column on table [T]. A column is the SQL identifier (an [Expression]/[Selectable])
 * and, through its concrete [NotNullColumn] / [NullableColumn] subtype, the property delegate
 * used inside the entity [N].
 *
 * Nullability is encoded in the type, not a runtime flag:
 *
 * ```kotlin
 * object Users : Table<App, User>("users", ::User) {
 *     val id by Column.UUID().primaryKey()          // non-null primary key
 *     val name by Column.Text()                      // non-null
 *     val note by Column.Text().nullable()           // nullable -> String?
 *     val createdAt by Column.Instant(name = "created_at")
 * }
 * ```
 *
 * [fieldKey] (the Kotlin property name) keys the value in [Entity.fields]; [name] is the
 * rendered SQL identifier. They differ only when a custom `name = ...` is given, so custom
 * SQL names never leak into entity internals or absent/null tracking.
 */
sealed class Column<Z, T: Table<*, N>, N: Entity>(
    private val table: T,
    /** Key under which the value is stored in [Entity.fields]; follows the Kotlin property name. */
    val fieldKey: String,
    /** Rendered SQL column identifier. Equals [fieldKey] unless a custom name was supplied. */
    val name: String,
    val nullable: kotlin.Boolean,
    override val columnType: ColumnType<Z>,
) : Expression, Operand<Z> {

    open fun init() {
        logger.trace { "init column $fieldKey (sql: $name)" }
        table.addColumn(fieldKey, this)
    }

    override fun toString(): String = name

    // A column renders to its (quoted) identifier in SQL, never as a bind parameter.
    // Inside a join it is qualified by its table so `users.id` and `orders.id` differ.
    override fun toSql(builder: ParamBuilder): String =
        if (builder.qualifyColumns) "${builder.dialect.quoteIdentifier(table.tableName)}.${builder.dialect.quoteIdentifier(name)}"
        else builder.dialect.quoteIdentifier(name)

    internal val tableRef: Table<*, *> get() = table

    /** Whether this column is part of the table's primary key. */
    internal var isPrimaryKey: kotlin.Boolean = false

    // The property delegate yields a fresh Column per access, so identity can't key a result row;
    // table+SQL-name is the stable, dialect-independent key (aggregates embed this for their target).
    override fun resultKey(): Any = "${table.tableName}.$name"

    // Converts a domain value to its bound form (e.g. enum -> name, @Serializable -> JsonElement)
    // before it reaches the ParamBuilder. Null passes through; built-in types are identity.
    @Suppress("UNCHECKED_CAST")
    internal fun bindParam(value: Any?): Any? =
        if (value == null) null else (columnType as ColumnType<Any?>).toParam(value)

    /**
     * A non-null column. Its entity property is `Z`: assigning `null` is a compile error, and
     * reading a field that was never assigned (or that the database returned as `NULL`) throws.
     */
    class NotNullColumn<Z, T: Table<*, N>, N: Entity>(table: T, fieldKey: String, name: String, columnType: ColumnType<Z>)
        : Column<Z, T, N>(table, fieldKey, name, nullable = false, columnType) {
        operator fun getValue(n: N, property: KProperty<*>): Z {
            logger.trace { "Get value $fieldKey" }
            if (!n.fields.containsKey(fieldKey)) error("Field '$fieldKey' is not present on ${tableRef.tableName}")
            @Suppress("UNCHECKED_CAST")
            return (n.fields[fieldKey] ?: error("Field '$fieldKey' is null but column '$name' is non-null")) as Z
        }

        operator fun setValue(n: N, property: KProperty<*>, z: Z) {
            logger.trace { "Set value $fieldKey" }
            n.fields[fieldKey] = z
        }
    }

    /** A nullable column. Its entity property is `Z?`: an absent field reads back as `null`. */
    class NullableColumn<Z, T: Table<*, N>, N: Entity>(table: T, fieldKey: String, name: String, columnType: ColumnType<Z>)
        : Column<Z, T, N>(table, fieldKey, name, nullable = true, columnType) {
        @Suppress("UNCHECKED_CAST")
        operator fun getValue(n: N, property: KProperty<*>): Z? {
            logger.trace { "Get value $fieldKey" }
            return n.fields[fieldKey] as Z?
        }

        operator fun setValue(n: N, property: KProperty<*>, z: Z?) {
            logger.trace { "Set value $fieldKey" }
            n.fields[fieldKey] = z
        }
    }

    companion object {
        /** Declares a column of any [ColumnType] — the open extension point. */
        fun <Z> of(type: ColumnType<Z>, name: String? = null): Spec<Z> = Spec(name, type)

        /** A column storing the enum [E] by name (text). */
        inline fun <reified E : Enum<E>> enum(name: String? = null): Spec<E> = of(enumColumnType<E>(), name)

        /** A column storing the `@Serializable` value [T] as JSON. */
        inline fun <reified T> json(name: String? = null): Spec<T> = of(jsonColumnType<T>(), name)
    }

    // ---- column specs (the public declaration builders) ----

    /**
     * Entry-point spec for a non-null column. Resolves to a [NotNullColumn]; refine with
     * [nullable] or [primaryKey] (mutually exclusive — a nullable primary key cannot be
     * expressed).
     */
    open class Spec<Z>(private val name: String?, private val columnType: ColumnType<Z>) {
        operator fun <T: Table<*, N>, N: Entity> provideDelegate(table: T, property: KProperty<*>): ReadOnlyProperty<T, NotNullColumn<Z, T, N>> {
            val column = NotNullColumn<Z, T, N>(table, property.name, name ?: property.name, columnType).also { it.init() }
            return ReadOnlyProperty { _, _ -> column }
        }

        /** Makes the column nullable; its entity property becomes `Z?`. */
        fun nullable(): NullableSpec<Z> = NullableSpec(name, columnType)

        /** Marks the column as the (or part of the) primary key. Non-null by construction. */
        fun primaryKey(): PrimaryKeySpec<Z> = PrimaryKeySpec(name, columnType)
    }

    /** Spec for a nullable column. Has no [primaryKey] — nullable primary keys are not allowed. */
    class NullableSpec<Z> internal constructor(private val name: String?, private val columnType: ColumnType<Z>) {
        operator fun <T: Table<*, N>, N: Entity> provideDelegate(table: T, property: KProperty<*>): ReadOnlyProperty<T, NullableColumn<Z, T, N>> {
            val column = NullableColumn<Z, T, N>(table, property.name, name ?: property.name, columnType).also { it.init() }
            return ReadOnlyProperty { _, _ -> column }
        }
    }

    /** Spec for a primary-key column. Has no [nullable] — nullable primary keys are not allowed. */
    class PrimaryKeySpec<Z> internal constructor(private val name: String?, private val columnType: ColumnType<Z>) {
        operator fun <T: Table<*, N>, N: Entity> provideDelegate(table: T, property: KProperty<*>): ReadOnlyProperty<T, NotNullColumn<Z, T, N>> {
            val column = NotNullColumn<Z, T, N>(table, property.name, name ?: property.name, columnType)
                .also { it.isPrimaryKey = true; it.init() }
            return ReadOnlyProperty { _, _ -> column }
        }
    }

    // ---- the 14 typed column declarations ----

    class UUID(name: String? = null) : Spec<kotlin.uuid.Uuid>(name, UuidColumnType)
    class BigDecimal(name: String? = null) : Spec<com.ionspin.kotlin.bignum.decimal.BigDecimal>(name, BigDecimalColumnType)
    class Double(name: String? = null) : Spec<kotlin.Double>(name, DoubleColumnType)
    class Int(name: String? = null) : Spec<kotlin.Int>(name, IntColumnType)
    class Boolean(name: String? = null) : Spec<kotlin.Boolean>(name, BooleanColumnType)
    class Text(name: String? = null) : Spec<kotlin.String>(name, TextColumnType)
    class Instant(name: String? = null) : Spec<kotlinx.datetime.Instant>(name, InstantColumnType)
    class Json(name: String? = null) : Spec<JsonElement>(name, JsonColumnType)
    class Long(name: String? = null) : Spec<kotlin.Long>(name, LongColumnType)
    class Float(name: String? = null) : Spec<kotlin.Float>(name, FloatColumnType)
    class Short(name: String? = null) : Spec<kotlin.Short>(name, ShortColumnType)
    class LocalDate(name: String? = null) : Spec<kotlinx.datetime.LocalDate>(name, LocalDateColumnType)
    class LocalTime(name: String? = null) : Spec<kotlinx.datetime.LocalTime>(name, LocalTimeColumnType)
    class LocalDateTime(name: String? = null) : Spec<kotlinx.datetime.LocalDateTime>(name, LocalDateTimeColumnType)
    class Bytes(name: String? = null) : Spec<kotlin.ByteArray>(name, BytesColumnType)
}
