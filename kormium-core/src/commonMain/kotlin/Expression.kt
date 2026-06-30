package io.github.kormium

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.github.kormium.resultset.ResultSet
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.uuid.Uuid

/**
 * Collects bind values while an [Expression] or [Query] is rendered to SQL.
 * Instead of inlining values into the SQL string (which is open to SQL
 * injection), each value is registered under a generated name and replaced by a
 * placeholder that the database driver binds as a real parameter. Identifier
 * quoting and placeholder rendering are delegated to [dialect]; value conversion
 * to [typeMapper].
 */
class ParamBuilder(
    val dialect: Dialect,
    private val typeMapper: TypeMapper,
    qualifyColumns: Boolean = false,
) {
    /** When true, a [Column] renders as `"table"."col"` (needed to disambiguate joins / subqueries). */
    var qualifyColumns: Boolean = qualifyColumns
        private set

    private var counter = 0
    private val collected = LinkedHashMap<String, Any?>()

    /** The bind values gathered so far, keyed by their generated placeholder name. */
    val params: Map<String, Any?> get() = collected

    /** Registers [value] as a bind parameter and returns the placeholder to embed in the SQL. */
    fun bind(value: Any?): String {
        val name = "p${counter++}"
        collected[name] = typeMapper.toParameter(value)
        return dialect.renderBind(name, value)
    }

    /**
     * Renders [block] with columns qualified by their table, then restores the previous setting —
     * used inside a correlated subquery so an outer column (`users.id`) and an inner one
     * (`orders.userId`) don't collide. Parameters keep flowing into this same builder, in order.
     */
    internal fun <R> qualified(block: () -> R): R {
        val previous = qualifyColumns
        qualifyColumns = true
        try {
            return block()
        } finally {
            qualifyColumns = previous
        }
    }
}

interface Expression {
    fun toSql(builder: ParamBuilder): String
}

class Value(internal val value: Any?) : Expression {
    override fun toSql(builder: ParamBuilder): String = builder.bind(value)
}

// A stable, structural key for an operand of a computed [Selectable] (COALESCE, arithmetic): a
// nested selectable contributes its own key, a literal its (bound) value — so two computed
// expressions differ by their literals, not only their shape, and a projection reads back with a
// fresh instance. A non-selectable, non-literal operand falls back to identity (rare).
internal fun structuralKey(expr: Expression): Any = when (expr) {
    is Selectable<*> -> expr.resultKey()
    is Value -> "lit(${expr.value})"
    else -> expr
}

/**
 * Raw, unparameterized SQL fragment embedded verbatim. Unsafe with untrusted
 * input — prefer [Value] and the typed operators below. Use only for SQL you
 * fully control.
 */
class RawExpression(val expression: String) : Expression {
    override fun toSql(builder: ParamBuilder): String = expression
}

sealed class CompoundBooleanOp(
    private val operator: String,
    private val first: Expression,
    private val second: Expression,
) : Expression {
    override fun toSql(builder: ParamBuilder): String =
        "${render(first, builder)}$operator${render(second, builder)}"

    // Kotlin infix calls are all same-precedence and left-associative, so `a or b and c`
    // builds AndOp(OrOp(a, b), c). SQL gives AND higher precedence than OR, so rendering
    // operands bare would silently change the query's meaning. Parenthesize any nested
    // compound op with a different operator; same-operator chains are associative and
    // stay flat.
    private fun render(expr: Expression, builder: ParamBuilder): String {
        val sql = expr.toSql(builder)
        return if (expr is CompoundBooleanOp && expr.operator != operator) "($sql)" else sql
    }
}

class AndOp(first: Expression, second: Expression) : CompoundBooleanOp(" AND ", first, second)

infix fun <T : Expression, T2 : Expression> T.and(other: T2): Expression = AndOp(this, other)

/**
 * Represents a logical operator that performs an `or` operation between all the specified [expressions].
 */
class OrOp(first: Expression, second: Expression) : CompoundBooleanOp(" OR ", first, second)

infix fun <T : Expression, T2 : Expression> T.or(other: T2): Expression = OrOp(this, other)


abstract class ComparisonOp(
    /** Returns the left-hand side operand. */
    val first: Expression,
    /** Returns the right-hand side operand. */
    val second: Expression,
    /** Returns the symbol of the comparison operation. */
    val opSign: String,
) : Expression {
    override fun toSql(builder: ParamBuilder): String =
        "${first.toSql(builder)} $opSign ${second.toSql(builder)}"
}

// Comparison operators come in two forms: column-to-expression (e.g. column to column)
// and the common column-to-value form, which is typed — `Users.age eq 18`, not a string —
// so the value type must match the column's.

class EqOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, "=")

infix fun <T : Expression, T2 : Expression> T.eq(other: T2): Expression = EqOp(this, other)
infix fun <Z> Column<Z, *, *>.eq(value: Z): Expression = EqOp(this, Value(bindParam(value)))

/** Checks that the operands are not equal. */
class NeqOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, "<>")

infix fun <T : Expression, T2 : Expression> T.neq(other: T2): Expression = NeqOp(this, other)
infix fun <Z> Column<Z, *, *>.neq(value: Z): Expression = NeqOp(this, Value(bindParam(value)))

/** Checks that the left operand is less than the right. */
class LessOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, "<")

infix fun <T : Expression, T2 : Expression> T.less(other: T2): Expression = LessOp(this, other)
infix fun <Z> Column<Z, *, *>.less(value: Z): Expression = LessOp(this, Value(bindParam(value)))

/** Checks that the left operand is less than or equal to the right. */
class LessEqOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, "<=")

infix fun <T : Expression, T2 : Expression> T.lessEq(other: T2): Expression = LessEqOp(this, other)
infix fun <Z> Column<Z, *, *>.lessEq(value: Z): Expression = LessEqOp(this, Value(bindParam(value)))

/** Checks that the left operand is greater than the right. */
class GreaterOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, ">")

infix fun <T : Expression, T2 : Expression> T.gt(other: T2): Expression = GreaterOp(this, other)
infix fun <Z> Column<Z, *, *>.gt(value: Z): Expression = GreaterOp(this, Value(bindParam(value)))

/** Checks that the left operand is greater than or equal to the right. */
class GreaterEqOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, ">=")

infix fun <T : Expression, T2 : Expression> T.gtEq(other: T2): Expression = GreaterEqOp(this, other)
infix fun <Z> Column<Z, *, *>.gtEq(value: Z): Expression = GreaterEqOp(this, Value(bindParam(value)))

/** `column IN (v1, v2, ...)`. An empty list renders to `FALSE` (matches nothing). */
class InListOp(private val column: Expression, private val values: List<*>) : Expression {
    override fun toSql(builder: ParamBuilder): String =
        if (values.isEmpty()) FalseExpression.toSql(builder)
        else "${column.toSql(builder)} IN (${values.joinToString(", ") { builder.bind(it) }})"
}

infix fun <Z> Column<Z, *, *>.inList(values: List<Z>): Expression = InListOp(this, values.map { bindParam(it) })

/** The SQL `FALSE` literal — what a predicate over an empty set (`inList`, `between`) renders to. */
internal object FalseExpression : Expression {
    override fun toSql(builder: ParamBuilder): String = "FALSE"
}

/** `expr BETWEEN lo AND hi` — both bounds inclusive, matching SQL and Kotlin's `lo..hi`. */
class BetweenOp(private val expr: Expression, private val low: Expression, private val high: Expression) : Expression {
    override fun toSql(builder: ParamBuilder): String =
        "${expr.toSql(builder)} BETWEEN ${low.toSql(builder)} AND ${high.toSql(builder)}"
}

// `column between lo..hi` — inclusive both ends (SQL BETWEEN == Kotlin `..`). The Comparable bound
// admits exactly the orderable column types (numbers, dates, text) and rejects Json/Bytes/Uuid at
// compile time, where BETWEEN is meaningless. A reversed/empty range (lo > hi) renders to FALSE,
// like an empty `inList`. Bounds bind through the column's converter, as in `eq` / `gtEq`.
infix fun <Z : Comparable<Z>> Column<Z, *, *>.between(range: ClosedRange<Z>): Expression =
    if (range.isEmpty()) FalseExpression
    else BetweenOp(this, Value(bindParam(range.start)), Value(bindParam(range.endInclusive)))

/** `(a + b) between lo..hi` — `between` over an arithmetic operand. */
infix fun <Z : Comparable<Z>> NumericExpr<Z>.between(range: ClosedRange<Z>): Expression =
    if (range.isEmpty()) FalseExpression
    else BetweenOp(this, columnType.lit(range.start), columnType.lit(range.endInclusive))

/** `column LIKE pattern` (text columns only). */
class LikeOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, "LIKE")

infix fun Column<String, *, *>.like(pattern: String): Expression = LikeOp(this, Value(pattern))

/** `column IS NULL` / `column IS NOT NULL`. */
class IsNullOp(private val column: Expression, private val negated: Boolean) : Expression {
    override fun toSql(builder: ParamBuilder): String =
        "${column.toSql(builder)} IS ${if (negated) "NOT " else ""}NULL"
}

fun Column<*, *, *>.isNull(): Expression = IsNullOp(this, false)
fun Column<*, *, *>.isNotNull(): Expression = IsNullOp(this, true)

// `column eq null` / `column neq null` render as IS [NOT] NULL. The `Nothing?` parameter
// makes the null literal bind here instead of the typed `eq(value: Z)` overload, so the
// comparison vocabulary stays uniform (`note eq null` reads like `age gtEq 18`).
infix fun Column<*, *, *>.eq(value: Nothing?): Expression = IsNullOp(this, false)
infix fun Column<*, *, *>.neq(value: Nothing?): Expression = IsNullOp(this, true)

/**
 * A typed numeric operand: a [Column] participates via the operators below, and every arithmetic
 * result is itself a [NumericExpr] of the same type [Z], so expressions chain and nest while
 * staying type-checked (`(base + bonus) * 2`). Carrying [columnType] lets a literal on either side
 * bind through the originating column's converter rather than a guessed mapping.
 */
interface NumericExpr<Z> : Selectable<Z> {
    val columnType: ColumnType<Z>
}

/**
 * Arithmetic node: renders `left op right`, binding any literal as a parameter. A nested
 * [ArithmeticOp] operand is parenthesized so SQL precedence matches the Kotlin expression that
 * built it (`(a + b) * c`, not `a + b * c`). As a [Selectable] it can be read from a `select(...)`
 * projection; the result is read through [columnType] and is null when any operand is.
 */
class ArithmeticOp<Z>(
    private val left: Expression,
    private val right: Expression,
    private val opSign: String,
    override val columnType: ColumnType<Z>,
) : NumericExpr<Z> {
    override fun toSql(builder: ParamBuilder): String = "${render(left, builder)} $opSign ${render(right, builder)}"

    override fun read(rs: ResultSet, index: Int, typeMapper: TypeMapper): Z? = columnType.read(rs, index)

    override fun resultKey(): Any = "(${structuralKey(left)} $opSign ${structuralKey(right)})"

    private fun render(expr: Expression, builder: ParamBuilder): String {
        val sql = expr.toSql(builder)
        return if (expr is ArithmeticOp<*>) "($sql)" else sql
    }
}

@Suppress("UNCHECKED_CAST")
private fun <Z> ColumnType<Z>.lit(value: Z): Value = Value(if (value == null) null else (this as ColumnType<Any?>).toParam(value))

// Arithmetic is generic over the column's value type Z and closed under itself: each operator takes
// a same-typed literal `Z`, a same-typed `Column`, or another `NumericExpr<Z>`, and returns a
// `NumericExpr<Z>`. So a wrong-typed operand is a compile error, and results nest (the left operand
// always carries the result's column type). Usable in `WHERE` or an `update { }` `set`.
operator fun <Z> Column<Z, *, *>.plus(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "+", columnType)
operator fun <Z> Column<Z, *, *>.plus(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "+", columnType)
operator fun <Z> Column<Z, *, *>.plus(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "+", columnType)
operator fun <Z> NumericExpr<Z>.plus(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "+", columnType)
operator fun <Z> NumericExpr<Z>.plus(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "+", columnType)
operator fun <Z> NumericExpr<Z>.plus(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "+", columnType)

operator fun <Z> Column<Z, *, *>.minus(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "-", columnType)
operator fun <Z> Column<Z, *, *>.minus(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "-", columnType)
operator fun <Z> Column<Z, *, *>.minus(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "-", columnType)
operator fun <Z> NumericExpr<Z>.minus(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "-", columnType)
operator fun <Z> NumericExpr<Z>.minus(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "-", columnType)
operator fun <Z> NumericExpr<Z>.minus(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "-", columnType)

operator fun <Z> Column<Z, *, *>.times(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "*", columnType)
operator fun <Z> Column<Z, *, *>.times(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "*", columnType)
operator fun <Z> Column<Z, *, *>.times(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "*", columnType)
operator fun <Z> NumericExpr<Z>.times(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "*", columnType)
operator fun <Z> NumericExpr<Z>.times(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "*", columnType)
operator fun <Z> NumericExpr<Z>.times(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "*", columnType)

operator fun <Z> Column<Z, *, *>.div(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "/", columnType)
operator fun <Z> Column<Z, *, *>.div(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "/", columnType)
operator fun <Z> Column<Z, *, *>.div(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "/", columnType)
operator fun <Z> NumericExpr<Z>.div(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "/", columnType)
operator fun <Z> NumericExpr<Z>.div(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "/", columnType)
operator fun <Z> NumericExpr<Z>.div(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "/", columnType)

operator fun <Z> Column<Z, *, *>.rem(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "%", columnType)
operator fun <Z> Column<Z, *, *>.rem(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "%", columnType)
operator fun <Z> Column<Z, *, *>.rem(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "%", columnType)
operator fun <Z> NumericExpr<Z>.rem(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "%", columnType)
operator fun <Z> NumericExpr<Z>.rem(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "%", columnType)
operator fun <Z> NumericExpr<Z>.rem(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "%", columnType)

// Compare a nested arithmetic expression to a same-typed literal: `(likes - dislikes) gtEq 100`.
// (NumericExpr-vs-Column/Expression already works through the generic comparison operators.)
infix fun <Z> NumericExpr<Z>.eq(value: Z): Expression = EqOp(this, columnType.lit(value))
infix fun <Z> NumericExpr<Z>.neq(value: Z): Expression = NeqOp(this, columnType.lit(value))
infix fun <Z> NumericExpr<Z>.less(value: Z): Expression = LessOp(this, columnType.lit(value))
infix fun <Z> NumericExpr<Z>.lessEq(value: Z): Expression = LessEqOp(this, columnType.lit(value))
infix fun <Z> NumericExpr<Z>.gt(value: Z): Expression = GreaterOp(this, columnType.lit(value))
infix fun <Z> NumericExpr<Z>.gtEq(value: Z): Expression = GreaterEqOp(this, columnType.lit(value))

/**
 * A typed string-valued SQL expression. A string [Column] yields one via the scalar functions
 * below (`lower()`, `upper()`, `trim()`, `ltrim()`, `rtrim()`), and each returns another
 * [StringExpr], so they chain (`name.trim().lower()`). Being a [Selectable] it can also be read
 * back from a row in `select(...)`. Compare it with `eq` / `neq` / `like` / `gt` / `gtEq` /
 * `less` / `lessEq` against a `String` literal, or — through the generic expression operators —
 * against another [StringExpr] (`a.lower() eq b.lower()`).
 */
interface StringExpr : Selectable<String>

/**
 * A scalar SQL function rendered as `FN(arg, ...)`. The string-returning ones are [StringExpr],
 * so they compose with the string predicates and chain. The result key is structural (the
 * function over its arguments' keys), so a projected function reads back with a fresh instance.
 */
class StringFunction(private val fn: String, private val args: List<Expression>) : StringExpr {
    override fun toSql(builder: ParamBuilder): String = "$fn(${args.joinToString(", ") { it.toSql(builder) }})"
    override fun read(rs: ResultSet, index: Int, typeMapper: TypeMapper): String? = rs.getString(index)
    override fun resultKey(): Any =
        "$fn(${args.joinToString(", ") { structuralKey(it).toString() }})"
}

// Scalar string functions, defined on both Column<String> and StringExpr so they chain like the
// arithmetic operators do. LOWER/UPPER/TRIM/LTRIM/RTRIM are standard and render identically on
// PostgreSQL, MySQL and SQLite, so no dialect hook is needed.
fun Column<String, *, *>.lower(): StringExpr = StringFunction("LOWER", listOf(this))
fun StringExpr.lower(): StringExpr = StringFunction("LOWER", listOf(this))
fun Column<String, *, *>.upper(): StringExpr = StringFunction("UPPER", listOf(this))
fun StringExpr.upper(): StringExpr = StringFunction("UPPER", listOf(this))
fun Column<String, *, *>.trim(): StringExpr = StringFunction("TRIM", listOf(this))
fun StringExpr.trim(): StringExpr = StringFunction("TRIM", listOf(this))
fun Column<String, *, *>.ltrim(): StringExpr = StringFunction("LTRIM", listOf(this))
fun StringExpr.ltrim(): StringExpr = StringFunction("LTRIM", listOf(this))
fun Column<String, *, *>.rtrim(): StringExpr = StringFunction("RTRIM", listOf(this))
fun StringExpr.rtrim(): StringExpr = StringFunction("RTRIM", listOf(this))

/**
 * The number of **characters** in a string, as an `Int` (a [NumericExpr], so it compares, does
 * arithmetic, and reads from a `select(...)` projection). Renders the dialect's character-length
 * function — `LENGTH` on PostgreSQL/SQLite, `CHAR_LENGTH` on MySQL (whose `LENGTH` counts bytes).
 */
class LengthOp(private val arg: Expression) : NumericExpr<Int> {
    override val columnType: ColumnType<Int> = IntColumnType
    override fun toSql(builder: ParamBuilder): String = builder.dialect.renderCharLength(arg.toSql(builder))
    override fun read(rs: ResultSet, index: Int, typeMapper: TypeMapper): Int? = columnType.read(rs, index)
    override fun resultKey(): Any = "CHAR_LENGTH(${structuralKey(arg)})"
}

fun Column<String, *, *>.length(): NumericExpr<Int> = LengthOp(this)
fun StringExpr.length(): NumericExpr<Int> = LengthOp(this)

// Comparing a StringExpr to a String literal. (StringExpr-to-StringExpr comparison already works
// through the generic `Expression` operators above.) `like` has no generic form, so both its
// literal and StringExpr forms are provided here. Note: a bare string comparison follows the
// engine's collation (see docs) — wrap both sides in `lower()` for deterministic case folding.
infix fun StringExpr.like(pattern: String): Expression = LikeOp(this, Value(pattern))
infix fun StringExpr.like(other: StringExpr): Expression = LikeOp(this, other)
infix fun StringExpr.eq(value: String): Expression = EqOp(this, Value(value))
infix fun StringExpr.neq(value: String): Expression = NeqOp(this, Value(value))
infix fun StringExpr.less(value: String): Expression = LessOp(this, Value(value))
infix fun StringExpr.lessEq(value: String): Expression = LessEqOp(this, Value(value))
infix fun StringExpr.gt(value: String): Expression = GreaterOp(this, Value(value))
infix fun StringExpr.gtEq(value: String): Expression = GreaterEqOp(this, Value(value))

/**
 * `COALESCE(a, b, ...)` — the first non-null argument. It is a [Selectable] (readable from a
 * `select(...)` projection) and carries the [columnType] of its source column, both to read the
 * result back and to bind a compared literal through the right converter. Compare it with a typed
 * literal via the operators below, or with another expression through the generic operators. The
 * value is non-null when the last argument is (e.g. a literal default), so `row[...]` is safe then;
 * otherwise read it with `getOrNull`.
 */
class CoalesceOp<Z> internal constructor(
    internal val args: List<Expression>,
    internal val columnType: ColumnType<Z>,
) : Selectable<Z> {
    override fun toSql(builder: ParamBuilder): String = "COALESCE(${args.joinToString(", ") { it.toSql(builder) }})"
    override fun read(rs: ResultSet, index: Int, typeMapper: TypeMapper): Z? = columnType.read(rs, index)
    override fun resultKey(): Any = "COALESCE(${args.joinToString(", ") { structuralKey(it).toString() }})"
}

/** `COALESCE("col", default)` — read a nullable column with a fallback; the default binds through the column's converter. */
fun <Z> Column<Z, *, *>.coalesce(default: Z): CoalesceOp<Z> = CoalesceOp(listOf(this, Value(bindParam(default))), columnType)

/** `COALESCE("col", "c2", "c3", ...)` — the first non-null of this column and the [others], in order. */
fun <Z> Column<Z, *, *>.coalesce(vararg others: Column<Z, *, *>): CoalesceOp<Z> =
    CoalesceOp(listOf(this, *others), columnType)

/** Extends a `COALESCE` with more columns: `a.coalesce(b).coalesce(c, d)` → `COALESCE(a, b, c, d)`. */
fun <Z> CoalesceOp<Z>.coalesce(vararg others: Column<Z, *, *>): CoalesceOp<Z> =
    CoalesceOp(args + others, columnType)

/** Appends a literal fallback to a `COALESCE`, typically last: `a.coalesce(b).coalesce("default")`. */
fun <Z> CoalesceOp<Z>.coalesce(default: Z): CoalesceOp<Z> =
    CoalesceOp(args + columnType.lit(default), columnType)

// Comparing a COALESCE to a typed literal binds it through the source column's converter.
// (COALESCE-to-expression comparison already works through the generic operators above.)
infix fun <Z> CoalesceOp<Z>.eq(value: Z): Expression = EqOp(this, columnType.lit(value))
infix fun <Z> CoalesceOp<Z>.neq(value: Z): Expression = NeqOp(this, columnType.lit(value))
infix fun <Z> CoalesceOp<Z>.less(value: Z): Expression = LessOp(this, columnType.lit(value))
infix fun <Z> CoalesceOp<Z>.lessEq(value: Z): Expression = LessEqOp(this, columnType.lit(value))
infix fun <Z> CoalesceOp<Z>.gt(value: Z): Expression = GreaterOp(this, columnType.lit(value))
infix fun <Z> CoalesceOp<Z>.gtEq(value: Z): Expression = GreaterEqOp(this, columnType.lit(value))

/**
 * A searched `CASE WHEN cond THEN value ... ELSE default END`. It is a [Selectable] (readable from
 * a `select(...)` projection) carrying the [columnType] that reads the result back and binds each
 * branch value. Because its identity is the built expression, hold it in a `val` to read it back
 * from a row (`val tier = case { … }; row[tier]`). Compare it to a typed literal with the operators
 * below, or to another expression through the generic operators.
 */
class CaseOp<Z> internal constructor(
    private val branches: List<Pair<Expression, Expression>>,
    private val elseValue: Expression?,
    internal val columnType: ColumnType<Z>,
) : Selectable<Z> {
    override fun toSql(builder: ParamBuilder): String {
        val whens = branches.joinToString(" ") { (cond, value) -> "WHEN ${cond.toSql(builder)} THEN ${value.toSql(builder)}" }
        val elseSql = elseValue?.let { " ELSE ${it.toSql(builder)}" }.orEmpty()
        return "CASE $whens$elseSql END"
    }

    override fun read(rs: ResultSet, index: Int, typeMapper: TypeMapper): Z? = columnType.read(rs, index)

    // A CASE's value depends on its branch literals, which render as placeholders — so two CASEs
    // can share a rendered SQL string. Key by instance instead; hold the CASE in a val to read it.
    override fun resultKey(): Any = this
}

/** Builds the branches of a [case]. `whenever(cond) then value` adds a branch; `otherwise(value)` sets the ELSE. */
class CaseBuilder<Z> internal constructor(private val columnType: ColumnType<Z>) {
    internal val branches = mutableListOf<Pair<Expression, Expression>>()
    internal var elseValue: Expression? = null

    fun whenever(condition: Expression): WhenStep = WhenStep(condition)

    inner class WhenStep internal constructor(private val condition: Expression) {
        infix fun then(value: Z) {
            branches += condition to columnType.lit(value)
        }
    }

    fun otherwise(value: Z) {
        elseValue = columnType.lit(value)
    }
}

/**
 * A searched `CASE` whose result type is given explicitly — use this for enum / custom column
 * types, where the reader cannot be inferred from [Z]:
 * `case(StatusColumnType) { whenever(...) then Status.ACTIVE; otherwise Status.INACTIVE }`.
 */
fun <Z> case(columnType: ColumnType<Z>, block: CaseBuilder<Z>.() -> Unit): CaseOp<Z> {
    val builder = CaseBuilder(columnType).apply(block)
    require(builder.branches.isNotEmpty()) { "case { } needs at least one `whenever(...) then ...`" }
    return CaseOp(builder.branches, builder.elseValue, columnType)
}

/**
 * A searched `CASE` whose result type is inferred from the branch values for the built-in types
 * (String, the integer and floating types, Boolean, BigDecimal, Instant, the date/time types, Uuid):
 *
 * ```kotlin
 * val tier = case {
 *     whenever(Users.age gtEq 65) then "senior"
 *     whenever(Users.age gtEq 18) then "adult"
 *     otherwise "minor"
 * }
 * ```
 *
 * For an enum or other custom-mapped result, use the [case] overload that takes a `ColumnType`.
 */
inline fun <reified Z> case(noinline block: CaseBuilder<Z>.() -> Unit): CaseOp<Z> {
    @Suppress("UNCHECKED_CAST")
    val columnType = when (Z::class) {
        String::class -> TextColumnType
        Int::class -> IntColumnType
        Long::class -> LongColumnType
        Short::class -> ShortColumnType
        Boolean::class -> BooleanColumnType
        Double::class -> DoubleColumnType
        Float::class -> FloatColumnType
        BigDecimal::class -> BigDecimalColumnType
        Instant::class -> InstantColumnType
        LocalDate::class -> LocalDateColumnType
        LocalTime::class -> LocalTimeColumnType
        LocalDateTime::class -> LocalDateTimeColumnType
        Uuid::class -> UuidColumnType
        else -> error(
            "case<${Z::class.simpleName}> can't infer how to read the result; " +
                "use case(columnType) { ... } with the column's ColumnType",
        )
    } as ColumnType<Z>
    return case(columnType, block)
}

infix fun <Z> CaseOp<Z>.eq(value: Z): Expression = EqOp(this, columnType.lit(value))
infix fun <Z> CaseOp<Z>.neq(value: Z): Expression = NeqOp(this, columnType.lit(value))
infix fun <Z> CaseOp<Z>.less(value: Z): Expression = LessOp(this, columnType.lit(value))
infix fun <Z> CaseOp<Z>.lessEq(value: Z): Expression = LessEqOp(this, columnType.lit(value))
infix fun <Z> CaseOp<Z>.gt(value: Z): Expression = GreaterOp(this, columnType.lit(value))
infix fun <Z> CaseOp<Z>.gtEq(value: Z): Expression = GreaterEqOp(this, columnType.lit(value))

/** Groups an expression in parentheses so it composes safely with surrounding `AND`/`OR`. */
class ParenExpression(private val expr: Expression) : Expression {
    override fun toSql(builder: ParamBuilder): String = "(${expr.toSql(builder)})"
}

/** Negates an expression: `NOT (expr)`. */
class NotOp(private val expr: Expression) : Expression {
    override fun toSql(builder: ParamBuilder): String = "NOT (${expr.toSql(builder)})"
}

fun not(expr: Expression): Expression = NotOp(expr)
