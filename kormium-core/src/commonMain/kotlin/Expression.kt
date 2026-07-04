package io.github.kormium

import io.github.kormium.resultset.ResultSet
import kotlin.time.Instant
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
public class ParamBuilder private constructor(
    public val dialect: Dialect,
    private val typeMapper: TypeMapper?,
    private val keyMode: Boolean,
    qualifyColumns: Boolean,
) {
    /** The normal builder: binds values as real parameters through [typeMapper]. */
    public constructor(dialect: Dialect, typeMapper: TypeMapper, qualifyColumns: Boolean = false) :
        this(dialect, typeMapper, keyMode = false, qualifyColumns = qualifyColumns)

    /** When true, a [Column] renders as `"table"."col"` (needed to disambiguate joins / subqueries). */
    public var qualifyColumns: Boolean = qualifyColumns
        private set

    private var counter = 0
    private val collected = LinkedHashMap<String, Any?>()

    /** The bind values gathered so far, keyed by their generated placeholder name. */
    public val params: Map<String, Any?> get() = collected

    /** Registers [value] as a bind parameter and returns the placeholder to embed in the SQL. */
    public fun bind(value: Any?): String {
        // Key mode inlines the literal instead of binding it (and never touches the type mapper),
        // so an expression can render a stable structural key that distinguishes its literals.
        if (keyMode) return "lit($value)"
        val name = "p${counter++}"
        collected[name] = typeMapper!!.toParameter(value)
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

    public companion object {
        // A builder that inlines literals instead of binding them, used to derive an expression's
        // structural result key (see CaseOp) — never executed, so the dialect only needs to be
        // deterministic (any fixed one), and no type mapper is required.
        internal fun forKey(): ParamBuilder =
            ParamBuilder(StandardDialect, typeMapper = null, keyMode = true, qualifyColumns = false)
    }
}

/**
 * A node in a SQL expression tree that renders itself to a SQL string. Everything that can appear in
 * a `WHERE` / `HAVING` / `SELECT` / `SET` position is one: a [Column], a literal [Value], a predicate
 * (built by `eq` / `gt` / `and` / …), a computed [Operand] (`COALESCE`, `CASE`, arithmetic), or a
 * verbatim [RawExpression]. [toSql] registers any compared values as bind parameters on the builder
 * rather than inlining them.
 */
public interface Expression {
    public fun toSql(builder: ParamBuilder): String
}

/** A bound literal value: renders as a bind-parameter placeholder, never inlined into the SQL. */
public class Value(internal val value: Any?) : Expression {
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
@DelicateKormiumApi
public class RawExpression(public val expression: String) : Expression {
    override fun toSql(builder: ParamBuilder): String = expression
}

internal sealed class CompoundBooleanOp(
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

internal class AndOp(first: Expression, second: Expression) : CompoundBooleanOp(" AND ", first, second)

public infix fun <T : Expression, T2 : Expression> T.and(other: T2): Expression = AndOp(this, other)

/**
 * Represents a logical operator that performs an `or` operation between all the specified [expressions].
 */
internal class OrOp(first: Expression, second: Expression) : CompoundBooleanOp(" OR ", first, second)

public infix fun <T : Expression, T2 : Expression> T.or(other: T2): Expression = OrOp(this, other)


internal abstract class ComparisonOp(
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

internal class EqOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, "=")

public infix fun <T : Expression, T2 : Expression> T.eq(other: T2): Expression = EqOp(this, other)

/** Checks that the operands are not equal. */
internal class NeqOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, "<>")

public infix fun <T : Expression, T2 : Expression> T.neq(other: T2): Expression = NeqOp(this, other)

/** Checks that the left operand is less than the right. */
internal class LessOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, "<")

public infix fun <T : Expression, T2 : Expression> T.lt(other: T2): Expression = LessOp(this, other)

/** Checks that the left operand is less than or equal to the right. */
internal class LessEqOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, "<=")

public infix fun <T : Expression, T2 : Expression> T.ltEq(other: T2): Expression = LessEqOp(this, other)

/** Checks that the left operand is greater than the right. */
internal class GreaterOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, ">")

public infix fun <T : Expression, T2 : Expression> T.gt(other: T2): Expression = GreaterOp(this, other)

/** Checks that the left operand is greater than or equal to the right. */
internal class GreaterEqOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, ">=")

public infix fun <T : Expression, T2 : Expression> T.gtEq(other: T2): Expression = GreaterEqOp(this, other)

// The typed-value forms of the comparison and membership operators are defined ONCE over [Operand]
// (a [Selectable] that carries its [ColumnType] — a Column, aggregate, arithmetic, COALESCE, CASE or
// string function), so every operand compares to a same-typed literal the same way, the literal binds
// through the operand's column type, and a new operand type composes for free. Operand-to-operand and
// column-to-column comparisons go through the generic `Expression` operators above.
public infix fun <Z> Operand<Z>.eq(value: Z): Expression = EqOp(this, columnType.lit(value))
public infix fun <Z> Operand<Z>.neq(value: Z): Expression = NeqOp(this, columnType.lit(value))
public infix fun <Z> Operand<Z>.lt(value: Z): Expression = LessOp(this, columnType.lit(value))
public infix fun <Z> Operand<Z>.ltEq(value: Z): Expression = LessEqOp(this, columnType.lit(value))
public infix fun <Z> Operand<Z>.gt(value: Z): Expression = GreaterOp(this, columnType.lit(value))
public infix fun <Z> Operand<Z>.gtEq(value: Z): Expression = GreaterEqOp(this, columnType.lit(value))

/** `column IN (v1, v2, ...)`. An empty list renders to `FALSE` (matches nothing). */
internal class InListOp(private val column: Expression, private val values: List<*>) : Expression {
    override fun toSql(builder: ParamBuilder): String =
        if (values.isEmpty()) FalseExpression.toSql(builder)
        else "${column.toSql(builder)} IN (${values.joinToString(", ") { builder.bind(it) }})"
}

public infix fun <Z> Operand<Z>.inList(values: List<Z>): Expression = InListOp(this, values.map { columnType.lit(it).value })

/** The SQL `FALSE` literal — what a predicate over an empty set (`inList`, `between`) renders to. */
internal object FalseExpression : Expression {
    override fun toSql(builder: ParamBuilder): String = "FALSE"
}

/** `expr BETWEEN lo AND hi` — both bounds inclusive, matching SQL and Kotlin's `lo..hi`. */
internal class BetweenOp(private val expr: Expression, private val low: Expression, private val high: Expression) : Expression {
    override fun toSql(builder: ParamBuilder): String =
        "${expr.toSql(builder)} BETWEEN ${low.toSql(builder)} AND ${high.toSql(builder)}"
}

// `operand between lo..hi` — inclusive both ends (SQL BETWEEN == Kotlin `..`). The Comparable bound
// admits exactly the orderable types (numbers, dates, text) and rejects Json/Bytes/Uuid at compile
// time, where BETWEEN is meaningless. A reversed/empty range (lo > hi) renders to FALSE, like an
// empty `inList`. Bounds bind through the operand's column type, as in `eq` / `gtEq`.
public infix fun <Z : Comparable<Z>> Operand<Z>.between(range: ClosedRange<Z>): Expression =
    if (range.isEmpty()) FalseExpression
    else BetweenOp(this, columnType.lit(range.start), columnType.lit(range.endInclusive))

/** `column LIKE pattern` (text operands only). */
internal class LikeOp(expr1: Expression, expr2: Expression) : ComparisonOp(expr1, expr2, "LIKE")

// `like` has no generic Expression form, so both the literal-pattern and operand-to-operand forms
// live here. A bare string comparison follows the engine collation (see docs) — wrap both sides in
// `lower()` for deterministic case folding.
public infix fun Operand<String>.like(pattern: String): Expression = LikeOp(this, Value(pattern))
public infix fun Operand<String>.like(other: Operand<String>): Expression = LikeOp(this, other)

/** `column IS NULL` / `column IS NOT NULL`. */
internal class IsNullOp(private val column: Expression, private val negated: Boolean) : Expression {
    override fun toSql(builder: ParamBuilder): String =
        "${column.toSql(builder)} IS ${if (negated) "NOT " else ""}NULL"
}

// `IS [NOT] NULL` on any operand — a column or a computed expression (`COALESCE` of nullable columns,
// a `CASE`, an aggregate, …). This is the general form; nullable columns also get the `eq null` sugar
// below. (A non-null column's `.isNull()` is allowed but always false — use it on the nullable ones.)
public fun Operand<*>.isNull(): Expression = IsNullOp(this, false)
public fun Operand<*>.isNotNull(): Expression = IsNullOp(this, true)

// `column eq null` / `column neq null` render as IS [NOT] NULL. The `Nothing?` parameter makes the
// null literal bind here instead of the typed `eq(value: Z)` overload, so the comparison vocabulary
// stays uniform (`note eq null` reads like `age gtEq 18`). Restricted to a NULLABLE column: a non-null
// column is never NULL, so `eq null` on one is a bug — and keeping this overload off non-null columns
// means a typed mismatch (`age eq "x"`) reports against the real `eq(value: Z)` candidate ("Int
// expected") instead of this one ("Nothing? expected"). A computed nullable expression uses `.isNull()`.
public infix fun Column.NullableColumn<*, *, *>.eq(value: Nothing?): Expression = IsNullOp(this, false)
public infix fun Column.NullableColumn<*, *, *>.neq(value: Nothing?): Expression = IsNullOp(this, true)

/**
 * A typed SQL operand: a [Selectable] that knows the [ColumnType] of the value it yields. Every
 * comparable expression is one — a [Column], an aggregate, arithmetic, COALESCE, CASE, a string
 * function — so the comparison / membership operators (`eq`, `gt`, `inList`, `between`, `like`) are
 * defined once over `Operand<Z>` instead of per type, and reading a row defaults to the column type.
 * Carrying [columnType] also lets a compared literal bind through the originating column's converter.
 */
public interface Operand<Z> : Selectable<Z> {
    public val columnType: ColumnType<Z>
    override fun read(rs: ResultSet, index: Int, typeMapper: TypeMapper): Z? = columnType.read(rs, index)
}

/**
 * A typed numeric operand: every arithmetic result is itself a [NumericExpr] of the same type [Z],
 * so expressions chain and nest while staying type-checked (`(base + bonus) * 2`).
 */
public interface NumericExpr<Z> : Operand<Z>

/**
 * Arithmetic node: renders `left op right`, binding any literal as a parameter. A nested
 * [ArithmeticOp] operand is parenthesized so SQL precedence matches the Kotlin expression that
 * built it (`(a + b) * c`, not `a + b * c`). As a [Selectable] it can be read from a `select(...)`
 * projection; the result is read through [columnType] and is null when any operand is.
 */
internal class ArithmeticOp<Z>(
    private val left: Expression,
    private val right: Expression,
    private val opSign: String,
    override val columnType: ColumnType<Z>,
) : NumericExpr<Z> {
    override fun toSql(builder: ParamBuilder): String = "${render(left, builder)} $opSign ${render(right, builder)}"

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
public operator fun <Z> Column<Z, *, *>.plus(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "+", columnType)
public operator fun <Z> Column<Z, *, *>.plus(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "+", columnType)
public operator fun <Z> Column<Z, *, *>.plus(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "+", columnType)
public operator fun <Z> NumericExpr<Z>.plus(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "+", columnType)
public operator fun <Z> NumericExpr<Z>.plus(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "+", columnType)
public operator fun <Z> NumericExpr<Z>.plus(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "+", columnType)

public operator fun <Z> Column<Z, *, *>.minus(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "-", columnType)
public operator fun <Z> Column<Z, *, *>.minus(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "-", columnType)
public operator fun <Z> Column<Z, *, *>.minus(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "-", columnType)
public operator fun <Z> NumericExpr<Z>.minus(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "-", columnType)
public operator fun <Z> NumericExpr<Z>.minus(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "-", columnType)
public operator fun <Z> NumericExpr<Z>.minus(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "-", columnType)

public operator fun <Z> Column<Z, *, *>.times(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "*", columnType)
public operator fun <Z> Column<Z, *, *>.times(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "*", columnType)
public operator fun <Z> Column<Z, *, *>.times(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "*", columnType)
public operator fun <Z> NumericExpr<Z>.times(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "*", columnType)
public operator fun <Z> NumericExpr<Z>.times(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "*", columnType)
public operator fun <Z> NumericExpr<Z>.times(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "*", columnType)

public operator fun <Z> Column<Z, *, *>.div(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "/", columnType)
public operator fun <Z> Column<Z, *, *>.div(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "/", columnType)
public operator fun <Z> Column<Z, *, *>.div(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "/", columnType)
public operator fun <Z> NumericExpr<Z>.div(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "/", columnType)
public operator fun <Z> NumericExpr<Z>.div(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "/", columnType)
public operator fun <Z> NumericExpr<Z>.div(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "/", columnType)

public operator fun <Z> Column<Z, *, *>.rem(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "%", columnType)
public operator fun <Z> Column<Z, *, *>.rem(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "%", columnType)
public operator fun <Z> Column<Z, *, *>.rem(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "%", columnType)
public operator fun <Z> NumericExpr<Z>.rem(value: Z): NumericExpr<Z> = ArithmeticOp(this, columnType.lit(value), "%", columnType)
public operator fun <Z> NumericExpr<Z>.rem(other: Column<Z, *, *>): NumericExpr<Z> = ArithmeticOp(this, other, "%", columnType)
public operator fun <Z> NumericExpr<Z>.rem(other: NumericExpr<Z>): NumericExpr<Z> = ArithmeticOp(this, other, "%", columnType)

/**
 * A typed string-valued SQL expression. A string [Column] yields one via the scalar functions
 * below (`lower()`, `upper()`, `trim()`, `ltrim()`, `rtrim()`), and each returns another
 * [StringExpr], so they chain (`name.trim().lower()`). Being a [Selectable] it can also be read
 * back from a row in `select(...)`. Compare it with `eq` / `neq` / `like` / `gt` / `gtEq` /
 * `lt` / `ltEq` against a `String` literal, or — through the generic expression operators —
 * against another [StringExpr] (`a.lower() eq b.lower()`).
 */
public interface StringExpr : Operand<String> {
    override val columnType: ColumnType<String> get() = TextColumnType
}

/**
 * A scalar SQL function rendered as `FN(arg, ...)`. The string-returning ones are [StringExpr],
 * so they compose with the string predicates and chain. The result key is structural (the
 * function over its arguments' keys), so a projected function reads back with a fresh instance.
 */
internal class StringFunction(private val fn: String, private val args: List<Expression>) : StringExpr {
    override fun toSql(builder: ParamBuilder): String = "$fn(${args.joinToString(", ") { it.toSql(builder) }})"
    override fun resultKey(): Any =
        "$fn(${args.joinToString(", ") { structuralKey(it).toString() }})"
}

// Scalar string functions, defined on both Column<String> and StringExpr so they chain like the
// arithmetic operators do. LOWER/UPPER/TRIM/LTRIM/RTRIM are standard and render identically on
// PostgreSQL, MySQL and SQLite, so no dialect hook is needed.
public fun Column<String, *, *>.lower(): StringExpr = StringFunction("LOWER", listOf(this))
public fun StringExpr.lower(): StringExpr = StringFunction("LOWER", listOf(this))
public fun Column<String, *, *>.upper(): StringExpr = StringFunction("UPPER", listOf(this))
public fun StringExpr.upper(): StringExpr = StringFunction("UPPER", listOf(this))
public fun Column<String, *, *>.trim(): StringExpr = StringFunction("TRIM", listOf(this))
public fun StringExpr.trim(): StringExpr = StringFunction("TRIM", listOf(this))
public fun Column<String, *, *>.ltrim(): StringExpr = StringFunction("LTRIM", listOf(this))
public fun StringExpr.ltrim(): StringExpr = StringFunction("LTRIM", listOf(this))
public fun Column<String, *, *>.rtrim(): StringExpr = StringFunction("RTRIM", listOf(this))
public fun StringExpr.rtrim(): StringExpr = StringFunction("RTRIM", listOf(this))

/**
 * The number of **characters** in a string, as an `Int` (a [NumericExpr], so it compares, does
 * arithmetic, and reads from a `select(...)` projection). Renders the dialect's character-length
 * function — `LENGTH` on PostgreSQL/SQLite, `CHAR_LENGTH` on MySQL (whose `LENGTH` counts bytes).
 */
internal class LengthOp(private val arg: Expression) : NumericExpr<Int> {
    override val columnType: ColumnType<Int> = IntColumnType
    override fun toSql(builder: ParamBuilder): String = builder.dialect.renderCharLength(arg.toSql(builder))
    override fun resultKey(): Any = "CHAR_LENGTH(${structuralKey(arg)})"
}

public fun Column<String, *, *>.length(): NumericExpr<Int> = LengthOp(this)
public fun StringExpr.length(): NumericExpr<Int> = LengthOp(this)

/**
 * `COALESCE(a, b, ...)` — the first non-null argument. It is a [Selectable] (readable from a
 * `select(...)` projection) and carries the [columnType] of its source column, both to read the
 * result back and to bind a compared literal through the right converter. Compare it with a typed
 * literal via the operators below, or with another expression through the generic operators. The
 * value is non-null when the last argument is (e.g. a literal default), so `row[...]` is safe then;
 * otherwise read it with `getOrNull`.
 */
public class CoalesceOp<Z> internal constructor(
    internal val args: List<Expression>,
    override val columnType: ColumnType<Z>,
) : Operand<Z> {
    override fun toSql(builder: ParamBuilder): String = "COALESCE(${args.joinToString(", ") { it.toSql(builder) }})"
    override fun resultKey(): Any = "COALESCE(${args.joinToString(", ") { structuralKey(it).toString() }})"
}

/** `COALESCE("col", default)` — read a nullable column with a fallback; the default binds through the column's converter. */
public fun <Z> Column<Z, *, *>.coalesce(default: Z): CoalesceOp<Z> = CoalesceOp(listOf(this, Value(bindParam(default))), columnType)

/** `COALESCE("col", "c2", "c3", ...)` — the first non-null of this column and the [others], in order. */
public fun <Z> Column<Z, *, *>.coalesce(vararg others: Column<Z, *, *>): CoalesceOp<Z> =
    CoalesceOp(listOf(this, *others), columnType)

/** Extends a `COALESCE` with more columns: `a.coalesce(b).coalesce(c, d)` → `COALESCE(a, b, c, d)`. */
public fun <Z> CoalesceOp<Z>.coalesce(vararg others: Column<Z, *, *>): CoalesceOp<Z> =
    CoalesceOp(args + others, columnType)

/** Appends a literal fallback to a `COALESCE`, typically last: `a.coalesce(b).coalesce("default")`. */
public fun <Z> CoalesceOp<Z>.coalesce(default: Z): CoalesceOp<Z> =
    CoalesceOp(args + columnType.lit(default), columnType)

/**
 * A searched `CASE WHEN cond THEN value ... ELSE default END`. It is a [Selectable] (readable from
 * a `select(...)` projection) carrying the [columnType] that reads the result back and binds each
 * branch value. Like the other computed expressions it is keyed structurally, so a freshly built,
 * identical `case { … }` reads back from a row — a `val` is handy for reuse, not required. Compare
 * it to a typed literal with the operators below, or to another expression through the generic ones.
 */
public class CaseOp<Z> internal constructor(
    private val branches: List<Pair<Expression, Expression>>,
    private val elseValue: Expression?,
    override val columnType: ColumnType<Z>,
) : Operand<Z> {
    override fun toSql(builder: ParamBuilder): String {
        val whens = branches.joinToString(" ") { (cond, value) -> "WHEN ${cond.toSql(builder)} THEN ${value.toSql(builder)}" }
        val elseSql = elseValue?.let { " ELSE ${it.toSql(builder)}" }.orEmpty()
        return "CASE $whens$elseSql END"
    }

    // Key off the CASE rendered with its branch literals inlined (key-mode builder): that captures
    // the conditions and values — including literals that normally render as placeholders — so two
    // CASEs differ by content, and a freshly built identical one reads back from a row.
    override fun resultKey(): Any = toSql(ParamBuilder.forKey())
}

/** Builds the branches of a [case]. `whenever(cond) then value` adds a branch; `otherwise(value)` sets the ELSE. */
public class CaseBuilder<Z> internal constructor(private val columnType: ColumnType<Z>) {
    internal val branches = mutableListOf<Pair<Expression, Expression>>()
    internal var elseValue: Expression? = null

    public fun whenever(condition: Expression): WhenStep = WhenStep(condition)

    public inner class WhenStep internal constructor(private val condition: Expression) {
        public infix fun then(value: Z) {
            branches += condition to columnType.lit(value)
        }
    }

    public fun otherwise(value: Z) {
        elseValue = columnType.lit(value)
    }
}

/**
 * A searched `CASE` whose result type is given explicitly — use this for enum / custom column
 * types, where the reader cannot be inferred from [Z]:
 * `case(StatusColumnType) { whenever(...) then Status.ACTIVE; otherwise Status.INACTIVE }`.
 */
public fun <Z> case(columnType: ColumnType<Z>, block: CaseBuilder<Z>.() -> Unit): CaseOp<Z> {
    val builder = CaseBuilder(columnType).apply(block)
    require(builder.branches.isNotEmpty()) { "case { } needs at least one `whenever(...) then ...`" }
    return CaseOp(builder.branches, builder.elseValue, columnType)
}

/**
 * A searched `CASE` whose result type is inferred from the branch values for the built-in types
 * (String, the integer and floating types, Boolean, Instant, the date/time types, Uuid):
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
public inline fun <reified Z> case(noinline block: CaseBuilder<Z>.() -> Unit): CaseOp<Z> {
    @Suppress("UNCHECKED_CAST")
    val columnType = when (Z::class) {
        String::class -> TextColumnType
        Int::class -> IntColumnType
        Long::class -> LongColumnType
        Short::class -> ShortColumnType
        Boolean::class -> BooleanColumnType
        Double::class -> DoubleColumnType
        Float::class -> FloatColumnType
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

/** Groups an expression in parentheses so it composes safely with surrounding `AND`/`OR`. */
internal class ParenExpression(private val expr: Expression) : Expression {
    override fun toSql(builder: ParamBuilder): String = "(${expr.toSql(builder)})"
}

/** Negates an expression: `NOT (expr)`. */
internal class NotOp(private val expr: Expression) : Expression {
    override fun toSql(builder: ParamBuilder): String = "NOT (${expr.toSql(builder)})"
}

public fun not(expr: Expression): Expression = NotOp(expr)
