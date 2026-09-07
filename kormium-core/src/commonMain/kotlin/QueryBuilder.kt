package io.github.kormium

/**
 * Builder behind the block form of reads and mutations:
 *
 * ```kotlin
 * Users.find {
 *     where { Users.age gtEq 18 }
 *     where { Users.name like "A%" }
 *     orderBy DESC Users.age
 *     orderBy ASC Users.name
 *     limit = 50
 *     offset = 100
 * }
 * ```
 *
 * It is a thin, ergonomic layer over [Query]: an empty block builds `Query()` (no `WHERE`,
 * no ordering, no limit/offset). [Query] stays available for reusable/prebuilt queries.
 */
@KormiumDsl
public class QueryBuilderOf<out B : Backend> {
    private val conditions = mutableListOf<Expression>()
    private val orderings = LinkedHashMap<Selectable<*>, AscDescOrder>()

    /** `orderBy DESC Users.age`, `orderBy ASC Users.name`. Multiple calls keep their order. */
    public val orderBy: OrderByDsl = OrderByDsl(orderings)

    /** No `LIMIT` when left null. */
    public var limit: Int? = null

    /** No `OFFSET` when left null. */
    public var offset: Int? = null

    /**
     * The row-level lock this query takes, if any — the seam a dialect module's gated DSL writes
     * through. Application code sets it via [forUpdate] / [forShare], which only resolve when [B]
     * is a [RowLockingBackend]; assigning here bypasses that check (see [KormiumDialectApi]).
     */
    @KormiumDialectApi
    public var rowLock: RowLock? = null

    /**
     * Adds a predicate. Multiple `where { ... }` calls combine with `AND`; put complex
     * boolean logic inside a single block using `and` / `or` / `not(...)`.
     */
    public fun where(block: () -> Expression) {
        conditions += block()
    }

    @OptIn(KormiumDialectApi::class)
    internal fun build(): Query {
        // Reject negative limit/offset: toUInt() would wrap (-1 -> 4294967295) and render a
        // huge LIMIT instead of failing fast on what is almost always bad user input.
        require(limit == null || limit!! >= 0) { "limit must be >= 0, was $limit" }
        require(offset == null || offset!! >= 0) { "offset must be >= 0, was $offset" }
        val whereExpression = when (conditions.size) {
            0 -> null
            1 -> conditions[0]
            // Parenthesize each block so a block-internal `or` keeps the right precedence
            // when blocks are AND-combined: `(a OR b) AND c`, not `a OR b AND c`.
            else -> conditions.map { ParenExpression(it) as Expression }.reduce { acc, e -> AndOp(acc, e) }
        }
        return Query(
            whereExpression = whereExpression,
            limit = limit?.toUInt() ?: UInt.MAX_VALUE,
            offset = offset?.toUInt() ?: 0u,
            orderBy = orderings.ifEmpty { null },
            lock = rowLock,
        )
    }
}

/**
 * The portable query builder — [QueryBuilderOf] with the baseline [AnyBackend] tag. Every
 * existing `QueryBuilder` reference keeps working; a backend-specific scope hands its block a
 * builder carrying that backend's tag instead, which is what unlocks the gated DSL.
 */
public typealias QueryBuilder = QueryBuilderOf<AnyBackend>

/**
 * Infix ordering for [QueryBuilder.orderBy]: `orderBy DESC column`, `orderBy ASC column`. The
 * operand is any [Selectable] — a column, or a computed value like `Users.name.lower()` or
 * `Users.qty * 2` (for case-insensitive or computed ordering).
 */
public class OrderByDsl internal constructor(private val orderings: MutableMap<Selectable<*>, AscDescOrder>) {
    public infix fun ASC(expression: Selectable<*>) {
        orderings[expression] = AscDescOrder.ASC
    }

    public infix fun DESC(expression: Selectable<*>) {
        orderings[expression] = AscDescOrder.DESC
    }
}
