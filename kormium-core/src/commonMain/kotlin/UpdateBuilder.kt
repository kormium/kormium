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
public class UpdateBuilder {
    private val assignments = LinkedHashMap<Column<*, *, *>, Expression>()
    private val conditions = mutableListOf<Expression>()

    /** `Posts.views set (Posts.views + 1)`: assigns a SQL [Expression] to this column. */
    public infix fun <Z> Column<Z, *, *>.set(value: Expression) {
        assignments[this] = value
    }

    /** `Posts.pinned set false`: assigns a literal value, bound as a parameter. */
    public infix fun <Z> Column<Z, *, *>.set(value: Z) {
        assignments[this] = Value(bindParam(value))
    }

    /** Adds a predicate; multiple `where { ... }` calls combine with `AND` (see [QueryBuilder.where]). */
    public fun where(block: () -> Expression) {
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

/**
 * Builder behind the expression form of an upsert's `DO UPDATE` clause:
 *
 * ```kotlin
 * Counters.upsert(entity = row, onConflict = Counters.key) {
 *     Counters.hits set (Counters.hits + 1)   // SET "hits" = "hits" + 1
 *     Counters.seenAt set now                 // SET "seenAt" = $1
 * }
 * ```
 *
 * Unlike the entity form ([Scope.upsert] with a patch entity), each `SET` target is assigned an
 * [Expression], so the conflicting row can be updated **from its own stored value** — the atomic
 * counter `hits = hits + 1`, which a patch entity cannot express because it can only carry
 * literals. An unqualified column on the right-hand side means the row already in the table, and
 * reads that way on PostgreSQL, SQLite and MySQL/MariaDB alike.
 *
 * Two things this deliberately does not expose:
 *  - **the proposed row** (PostgreSQL/SQLite `excluded.col`): MySQL spells it `VALUES(col)`
 *    (deprecated since 8.0.20) and MariaDB has no `new.col` alias at all, so there is no rendering
 *    that is both portable and current. Left out rather than shipped as a backend-specific hole.
 *  - **a conditional `DO UPDATE ... WHERE`**: PostgreSQL and SQLite have it, MySQL has no such
 *    construct — the same portability line [ADR 0008] drew for `RETURNING` on `UPDATE`/`DELETE`.
 */
@KormiumDsl
public class UpsertBuilder {
    private val assignments = LinkedHashMap<Column<*, *, *>, Expression>()

    /** `Counters.hits set (Counters.hits + 1)`: assigns a SQL [Expression] to this column. */
    public infix fun <Z> Column<Z, *, *>.set(value: Expression) {
        assignments[this] = value
    }

    /** `Counters.seenAt set now`: assigns a literal value, bound as a parameter. */
    public infix fun <Z> Column<Z, *, *>.set(value: Z) {
        assignments[this] = Value(bindParam(value))
    }

    internal fun buildAssignments(): Map<Column<*, *, *>, Expression> {
        require(assignments.isNotEmpty()) { "upsert { } needs at least one set(...)" }
        return assignments
    }
}
