package io.github.kormium

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
    /** When true, a [Column] renders as `"table"."col"` (needed to disambiguate joins). */
    val qualifyColumns: Boolean = false,
) {
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
}

interface Expression {
    fun toSql(builder: ParamBuilder): String
}

class Value(private val value: Any?) : Expression {
    override fun toSql(builder: ParamBuilder): String = builder.bind(value)
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
        if (values.isEmpty()) "FALSE"
        else "${column.toSql(builder)} IN (${values.joinToString(", ") { builder.bind(it) }})"
}

infix fun <Z> Column<Z, *, *>.inList(values: List<Z>): Expression = InListOp(this, values.map { bindParam(it) })

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
interface NumericExpr<Z> : Expression {
    val columnType: ColumnType<Z>
}

/**
 * Arithmetic node: renders `left op right`, binding any literal as a parameter. A nested
 * [ArithmeticOp] operand is parenthesized so SQL precedence matches the Kotlin expression that
 * built it (`(a + b) * c`, not `a + b * c`).
 */
class ArithmeticOp<Z>(
    private val left: Expression,
    private val right: Expression,
    private val opSign: String,
    override val columnType: ColumnType<Z>,
) : NumericExpr<Z> {
    override fun toSql(builder: ParamBuilder): String = "${render(left, builder)} $opSign ${render(right, builder)}"

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

/** Groups an expression in parentheses so it composes safely with surrounding `AND`/`OR`. */
class ParenExpression(private val expr: Expression) : Expression {
    override fun toSql(builder: ParamBuilder): String = "(${expr.toSql(builder)})"
}

/** Negates an expression: `NOT (expr)`. */
class NotOp(private val expr: Expression) : Expression {
    override fun toSql(builder: ParamBuilder): String = "NOT (${expr.toSql(builder)})"
}

fun not(expr: Expression): Expression = NotOp(expr)
