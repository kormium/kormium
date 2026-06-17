package io.github.kormium

/**
 * Builder behind the expression form of `UPDATE`:
 *
 * ```kotlin
 * Posts.update {
 *     Posts.views set (Posts.views + 1)   // SET "views" = "views" + 1
 *     Posts.pinned set false              // SET "pinned" = $1
 *     where { Posts.id eq postId }
 * }
 * ```
 *
 * Unlike the entity form ([Scope.update] with a patch entity), each `SET` target is assigned an
 * [Expression], so a column can be updated from its own value (atomic `n = n + 1`) without raw SQL.
 * `UPDATE` takes no `ORDER BY` / `LIMIT` / `OFFSET`, so this builder exposes only `set` and `where`.
 */
@KormiumDsl
class UpdateBuilder {
    private val assignments = LinkedHashMap<Column<*, *, *>, Expression>()
    private val conditions = mutableListOf<Expression>()

    /** `Posts.views set (Posts.views + 1)`: assigns a SQL [Expression] to this column. */
    infix fun <Z> Column<Z, *, *>.set(value: Expression) {
        assignments[this] = value
    }

    /** `Posts.pinned set false`: assigns a literal value, bound as a parameter. */
    infix fun <Z> Column<Z, *, *>.set(value: Z) {
        assignments[this] = Value(bindParam(value))
    }

    /** Adds a predicate; multiple `where { ... }` calls combine with `AND` (see [QueryBuilder.where]). */
    fun where(block: () -> Expression) {
        conditions += block()
    }

    internal fun buildAssignments(): Map<Column<*, *, *>, Expression> {
        require(assignments.isNotEmpty()) { "update { } needs at least one set(...)" }
        return assignments
    }

    internal fun buildWhere(): Query {
        val whereExpression = when (conditions.size) {
            0 -> null
            1 -> conditions[0]
            else -> conditions.map { ParenExpression(it) as Expression }.reduce { acc, e -> AndOp(acc, e) }
        }
        return Query(whereExpression = whereExpression)
    }
}
