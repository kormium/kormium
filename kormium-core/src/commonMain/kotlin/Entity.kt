package io.github.kormium

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
 * An entity is not a DTO. It wraps an internal field map that distinguishes three states
 * per column: absent (never assigned — omitted from INSERT/UPDATE), explicit null
 * (assigned `null` — written as SQL `NULL`), and a concrete value. That storage is an
 * implementation detail: it is not part of the public API and entities are not
 * serializable. Map entities to your own DTOs for transport.
 */
public abstract class Entity protected constructor() {
    internal var fields: MutableMap<String, Any?> = mutableMapOf()

    /** Replaces the backing field storage. Used by Kormium when hydrating a row from the database. */
    internal fun replaceFields(fields: MutableMap<String, Any?>) {
        this.fields = fields
    }
}

/**
 * True when [column] has an assigned or loaded value on this entity, including an
 * explicit `null`. Use it to tell "set to null" from "never assigned".
 *
 * The column must belong to this entity's type: `user.isSet(Orders.id)` is a compile error.
 */
public fun <N : Entity> N.isSet(column: Column<*, *, N>): Boolean = fields.containsKey(column.fieldKey)

/**
 * Removes [column]'s value, returning it to absent state (so it is omitted from
 * INSERT/UPDATE again). `entity.note = null` means explicit null; `entity.unset(T.note)`
 * means absent.
 *
 * The column must belong to this entity's type: `user.unset(Orders.id)` is a compile error.
 */
public fun <N : Entity> N.unset(column: Column<*, *, N>) {
    fields.remove(column.fieldKey)
}
