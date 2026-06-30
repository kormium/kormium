package io.github.kormium

/**
 * `[NOT ]EXISTS (SELECT 1 FROM table WHERE predicate)`. The predicate is rendered with columns
 * qualified by their table, so a reference to an outer column (`users.id`) correlates correctly
 * against an inner one (`orders.userId`). Built by [any] / [none].
 */
class ExistsOp internal constructor(
    private val table: Table<*, *>,
    private val predicate: Expression,
    private val negated: Boolean,
) : Expression {
    override fun toSql(builder: ParamBuilder): String {
        val condition = builder.qualified { predicate.toSql(builder) }
        val prefix = if (negated) "NOT " else ""
        return "${prefix}EXISTS (SELECT 1 FROM ${table.qualifiedName(builder.dialect)} WHERE $condition)"
    }
}

/**
 * `EXISTS (SELECT 1 FROM this WHERE …)` — true when at least one row of this table matches the
 * [predicate]. Read like Kotlin's `any { }`. The predicate is an ordinary boolean expression and
 * may reference the outer query's columns to correlate:
 *
 * ```kotlin
 * Users.find { where { Orders.any { (Orders.userId eq Users.id) and (Orders.total gt 100) } } }
 * ```
 */
fun <T : Entity> Table<*, T>.any(predicate: () -> Expression): Expression = ExistsOp(this, predicate(), negated = false)

/**
 * `NOT EXISTS (SELECT 1 FROM this WHERE …)` — true when no row of this table matches the
 * [predicate]. Read like Kotlin's `none { }`:
 *
 * ```kotlin
 * Users.find { where { Orders.none { Orders.userId eq Users.id } } }   // users with no orders
 * ```
 */
fun <T : Entity> Table<*, T>.none(predicate: () -> Expression): Expression = ExistsOp(this, predicate(), negated = true)
