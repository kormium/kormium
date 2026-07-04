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
public sealed class Column<Z, T: Table<*, N>, N: Entity>(
    private val table: T,
    /** Key under which the value is stored in [Entity.fields]; follows the Kotlin property name. */
    public val fieldKey: String,
    /** Rendered SQL column identifier. Equals [fieldKey] unless a custom name was supplied. */
    public val name: String,
    public val nullable: kotlin.Boolean,
    override val columnType: ColumnType<Z>,
) : Expression, Operand<Z> {

    public open fun init() {
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
    public class NotNullColumn<Z, T: Table<*, N>, N: Entity>(table: T, fieldKey: String, name: String, columnType: ColumnType<Z>)
        : Column<Z, T, N>(table, fieldKey, name, nullable = false, columnType) {
        public operator fun getValue(n: N, property: KProperty<*>): Z {
            logger.trace { "Get value $fieldKey" }
            if (!n.fields.containsKey(fieldKey)) error("Field '$fieldKey' is not present on ${tableRef.tableName}")
            @Suppress("UNCHECKED_CAST")
            return (n.fields[fieldKey] ?: error("Field '$fieldKey' is null but column '$name' is non-null")) as Z
        }

        public operator fun setValue(n: N, property: KProperty<*>, z: Z) {
            logger.trace { "Set value $fieldKey" }
            n.fields[fieldKey] = z
        }
    }

    /** A nullable column. Its entity property is `Z?`: an absent field reads back as `null`. */
    public class NullableColumn<Z, T: Table<*, N>, N: Entity>(table: T, fieldKey: String, name: String, columnType: ColumnType<Z>)
        : Column<Z, T, N>(table, fieldKey, name, nullable = true, columnType) {
        @Suppress("UNCHECKED_CAST")
        public operator fun getValue(n: N, property: KProperty<*>): Z? {
            logger.trace { "Get value $fieldKey" }
            return n.fields[fieldKey] as Z?
        }

        public operator fun setValue(n: N, property: KProperty<*>, z: Z?) {
            logger.trace { "Set value $fieldKey" }
            n.fields[fieldKey] = z
        }
    }

    public companion object {
        /** Declares a column of any [ColumnType] — the open extension point. */
        public fun <Z> of(type: ColumnType<Z>, name: String? = null): Spec<Z> = Spec(name, type)

        /** A column storing the enum [E] by name (text). */
        public inline fun <reified E : Enum<E>> enum(name: String? = null): Spec<E> = of(enumColumnType<E>(), name)

        /** A column storing the `@Serializable` value [T] as JSON. */
        public inline fun <reified T> json(name: String? = null): Spec<T> = of(jsonColumnType<T>(), name)
    }

    // ---- column specs (the public declaration builders) ----

    /**
     * Entry-point spec for a non-null column. Resolves to a [NotNullColumn]; refine with
     * [nullable] or [primaryKey] (mutually exclusive — a nullable primary key cannot be
     * expressed).
     */
    public open class Spec<Z>(private val name: String?, private val columnType: ColumnType<Z>) {
        public operator fun <T: Table<*, N>, N: Entity> provideDelegate(table: T, property: KProperty<*>): ReadOnlyProperty<T, NotNullColumn<Z, T, N>> {
            val column = NotNullColumn<Z, T, N>(table, property.name, name ?: property.name, columnType).also { it.init() }
            return ReadOnlyProperty { _, _ -> column }
        }

        /** Makes the column nullable; its entity property becomes `Z?`. */
        public fun nullable(): NullableSpec<Z> = NullableSpec(name, columnType)

        /** Marks the column as the (or part of the) primary key. Non-null by construction. */
        public fun primaryKey(): PrimaryKeySpec<Z> = PrimaryKeySpec(name, columnType)
    }

    /** Spec for a nullable column. Has no [primaryKey] — nullable primary keys are not allowed. */
    public class NullableSpec<Z> internal constructor(private val name: String?, private val columnType: ColumnType<Z>) {
        public operator fun <T: Table<*, N>, N: Entity> provideDelegate(table: T, property: KProperty<*>): ReadOnlyProperty<T, NullableColumn<Z, T, N>> {
            val column = NullableColumn<Z, T, N>(table, property.name, name ?: property.name, columnType).also { it.init() }
            return ReadOnlyProperty { _, _ -> column }
        }
    }

    /** Spec for a primary-key column. Has no [nullable] — nullable primary keys are not allowed. */
    public class PrimaryKeySpec<Z> internal constructor(private val name: String?, private val columnType: ColumnType<Z>) {
        public operator fun <T: Table<*, N>, N: Entity> provideDelegate(table: T, property: KProperty<*>): ReadOnlyProperty<T, NotNullColumn<Z, T, N>> {
            val column = NotNullColumn<Z, T, N>(table, property.name, name ?: property.name, columnType)
                .also { it.isPrimaryKey = true; it.init() }
            return ReadOnlyProperty { _, _ -> column }
        }
    }

    // ---- the 13 typed column declarations (decimal columns live in kormium-decimal) ----

    public class UUID(name: String? = null) : Spec<kotlin.uuid.Uuid>(name, UuidColumnType)
    public class Double(name: String? = null) : Spec<kotlin.Double>(name, DoubleColumnType)
    public class Int(name: String? = null) : Spec<kotlin.Int>(name, IntColumnType)
    public class Boolean(name: String? = null) : Spec<kotlin.Boolean>(name, BooleanColumnType)
    public class Text(name: String? = null) : Spec<kotlin.String>(name, TextColumnType)
    public class Instant(name: String? = null) : Spec<kotlin.time.Instant>(name, InstantColumnType)
    public class Json(name: String? = null) : Spec<JsonElement>(name, JsonColumnType)
    public class Long(name: String? = null) : Spec<kotlin.Long>(name, LongColumnType)
    public class Float(name: String? = null) : Spec<kotlin.Float>(name, FloatColumnType)
    public class Short(name: String? = null) : Spec<kotlin.Short>(name, ShortColumnType)
    public class LocalDate(name: String? = null) : Spec<kotlinx.datetime.LocalDate>(name, LocalDateColumnType)
    public class LocalTime(name: String? = null) : Spec<kotlinx.datetime.LocalTime>(name, LocalTimeColumnType)
    public class LocalDateTime(name: String? = null) : Spec<kotlinx.datetime.LocalDateTime>(name, LocalDateTimeColumnType)
    public class Bytes(name: String? = null) : Spec<kotlin.ByteArray>(name, BytesColumnType)
}
