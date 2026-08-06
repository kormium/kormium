package io.github.kormium

/**
 * Marks a slot that was never assigned, as opposed to one holding SQL `NULL`. A private
 * sentinel: it never escapes core, so no user value can be mistaken for it.
 */
internal val ABSENT: Any = Any()

private val NO_VALUES: Array<Any?> = arrayOfNulls(0)

/**
 * Base class for row entities. User entities extend it with a no-arg constructor and
 * declare typed property delegates:
 *
 * ```kotlin
 * class User : Entity() {
 *     var id by Users.id
 *     var name by Users.name
 * }
 * ```
 *
 * An entity is not a DTO. It wraps internal field storage that distinguishes three states
 * per column: absent (never assigned — omitted from INSERT/UPDATE), explicit null
 * (assigned `null` — written as SQL `NULL`), and a concrete value. That storage is an
 * implementation detail: it is not part of the public API and entities are not
 * serializable. Map entities to your own DTOs for transport.
 *
 * Values live in [values], indexed by [Column.ordinal] — a column's position in its table's
 * declaration order. Ordinals are only unique within one table, so the array is valid for
 * exactly one table, recorded in [owner]. The same entity type may legally back columns of
 * *several* tables (both are `Column<*, *, ThisEntity>`, which the type system permits);
 * columns of any table other than [owner] fall back to [overflow], which stays null unless
 * such a column is actually touched.
 */
public abstract class Entity protected constructor() {
    /** Values for [owner]'s columns, indexed by ordinal. [ABSENT] marks a never-assigned slot. */
    internal var values: Array<Any?> = NO_VALUES

    /** The table whose ordinal space [values] uses; null until the first write. */
    internal var owner: Table<*, *>? = null

    /** Values for columns of any table other than [owner], keyed by field name. Usually null. */
    internal var overflow: MutableMap<String, Any?>? = null

    /** Installs a loaded row. Used by Kormium when hydrating from the database. */
    internal fun adopt(values: Array<Any?>, owner: Table<*, *>) {
        this.values = values
        this.owner = owner
        this.overflow = null
    }
}

/** Reads [column]'s slot, returning [ABSENT] when it was never assigned. */
internal inline fun Entity.slotGet(column: Column<*, *, *>): Any? {
    if (column.tableRef === owner) {
        val v = values
        val ordinal = column.ordinal
        return if (ordinal < v.size) v[ordinal] else ABSENT
    }
    // Rare: a column of some other table backing this same entity type.
    val extra = overflow ?: return ABSENT
    val key = column.fieldKey
    return if (extra.containsKey(key)) extra[key] else ABSENT
}

/** Assigns [column]'s slot. The first assignment fixes which table owns [Entity.values]. */
internal inline fun Entity.slotSet(column: Column<*, *, *>, value: Any?) {
    val table = column.tableRef
    val current = owner
    if (current == null) {
        owner = table
        values = Array(table.columnCount) { ABSENT }
        values[column.ordinal] = value
        return
    }
    if (table === current) {
        values[column.ordinal] = value
        return
    }
    (overflow ?: HashMap<String, Any?>().also { overflow = it })[column.fieldKey] = value
}

/** Returns [column]'s slot to the absent state. */
internal inline fun Entity.slotClear(column: Column<*, *, *>) {
    if (column.tableRef === owner) {
        val v = values
        if (column.ordinal < v.size) v[column.ordinal] = ABSENT
    } else {
        overflow?.remove(column.fieldKey)
    }
}

/**
 * True when [column] has an assigned or loaded value on this entity, including an
 * explicit `null`. Use it to tell "set to null" from "never assigned".
 *
 * The column must belong to this entity's type: `user.isSet(Orders.id)` is a compile error.
 */
public fun <N : Entity> N.isSet(column: Column<*, *, N>): Boolean = slotGet(column) !== ABSENT

/**
 * Removes [column]'s value, returning it to absent state (so it is omitted from
 * INSERT/UPDATE again). `entity.note = null` means explicit null; `entity.unset(T.note)`
 * means absent.
 *
 * The column must belong to this entity's type: `user.unset(Orders.id)` is a compile error.
 */
public fun <N : Entity> N.unset(column: Column<*, *, N>) {
    slotClear(column)
}
