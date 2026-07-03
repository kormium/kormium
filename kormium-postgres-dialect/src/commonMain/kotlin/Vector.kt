package io.github.kormium

import io.github.kormium.resultset.ResultSet

/**
 * A dense float32 vector — an embedding — stored in a Postgres `vector` column (the
 * [pgvector](https://github.com/pgvector/pgvector) extension). Backed by a [FloatArray] so a
 * high-dimensional embedding (1536+ dims is typical) stays unboxed, with value-based
 * [equals]/[hashCode]/[toString] so it keys result rows and tracks changes like the built-in types.
 *
 * [toString] renders the pgvector text form `[1.0,2.0,3.0]`, which doubles as the wire value bound to
 * a parameter (the way a [kotlinx.serialization.json.JsonElement] doubles as its own `jsonb` text) —
 * [PostgresDialect] adds the `::vector` cast so the server reads it as a real vector.
 *
 * Kormium does not own DDL, so the SQL column (`CREATE EXTENSION vector; ... embedding vector(1536)`)
 * is declared in raw SQL / a migration; [Column.Companion.Vector] only describes how a value round-trips.
 */
class Vector(val data: FloatArray) {
    /** The number of dimensions (array length). */
    val size: Int get() = data.size

    /** Builds a vector from a list of floats (e.g. the output of an embedding library). */
    constructor(values: List<Float>) : this(values.toFloatArray())

    /** The i-th component. */
    operator fun get(index: Int): Float = data[index]

    override fun equals(other: Any?): Boolean = this === other || (other is Vector && data.contentEquals(other.data))

    override fun hashCode(): Int = data.contentHashCode()

    /** The pgvector text form `[1.0,2.0,3.0]` — also the value bound as a parameter. */
    override fun toString(): String = data.joinToString(separator = ",", prefix = "[", postfix = "]")

    companion object {
        /** `Vector.of(0.1f, 0.2f, 0.3f)` — a vector from its components. */
        fun of(vararg values: Float): Vector = Vector(values)

        /** Parses the pgvector text form `[1,2,3]` (as read back from a row) into a [Vector]. */
        fun parse(text: String): Vector {
            val trimmed = text.trim()
            require(trimmed.length >= 2 && trimmed.first() == '[' && trimmed.last() == ']') {
                "not a pgvector value: '$text'"
            }
            val inner = trimmed.substring(1, trimmed.length - 1)
            if (inner.isBlank()) return Vector(FloatArray(0))
            val parts = inner.split(',')
            return Vector(FloatArray(parts.size) { parts[it].trim().toFloat() })
        }
    }
}

/**
 * A [ColumnType] for pgvector [Vector] values. Reads the `[1,2,3]` text a `vector` column returns and
 * parses it; on write, the [Vector] flows through as its own text form and [PostgresDialect] casts it
 * to `::vector`. An optional [dimensions] is validated on write — a mismatched length fails fast with
 * a clear message rather than as an opaque server error.
 */
class VectorColumnType(val dimensions: Int? = null) : ColumnType<Vector> {
    override fun read(rs: ResultSet, index: Int): Vector? = rs.getString(index)?.let(Vector::parse)

    override fun toParam(value: Vector): Any? {
        if (dimensions != null) require(value.size == dimensions) {
            "vector has ${value.size} dimensions but the column declares $dimensions"
        }
        return value
    }

    override val description: String get() = if (dimensions != null) "Vector($dimensions)" else "Vector"
}

/**
 * Declares a pgvector column storing a [Vector] embedding — uniform with the built-in
 * `Column.Text()` / `Column.Json()` declarations:
 *
 * ```kotlin
 * object Docs : Table<App, Doc>("docs", ::Doc) {
 *     val embedding by Column.Vector(dimensions = 1536)
 * }
 * ```
 *
 * [dimensions], when given, is validated on every write. Refine with `.nullable()` as usual.
 */
fun Column.Companion.Vector(dimensions: Int? = null, name: String? = null): Column.Spec<Vector> =
    Column.of(VectorColumnType(dimensions), name)

/**
 * A vector similarity metric. Maps to a pgvector distance operator via [distance]. For all three,
 * **smaller is more similar**, so nearest-neighbour search is an ascending `orderBy`.
 */
enum class VectorMetric {
    /** Euclidean (L2) distance — straight-line distance; accounts for magnitude. pgvector `<->`. */
    EUCLIDEAN,

    /** Cosine distance (`1 - cosine similarity`) — compares direction, ignores magnitude. pgvector `<=>`. */
    COSINE,

    /** Negative inner product — pgvector negates the dot product so that smaller stays more similar. `<#>`. */
    DOT,
}

// The pgvector operator for a metric. Kept here (not on the neutral enum) so VectorMetric stays free of
// Postgres syntax — the seam to reuse when a second backend (MariaDB VEC_DISTANCE_*) is added.
private fun VectorMetric.pgOperator(): String = when (this) {
    VectorMetric.EUCLIDEAN -> "<->"
    VectorMetric.COSINE -> "<=>"
    VectorMetric.DOT -> "<#>"
}

// A pgvector distance node: renders `(left <op> right)` and yields a Double, so it slots straight into
// `orderBy` for KNN search, into `where` for a radius filter, or a `select(...)` projection to read the
// score back. The result key is structural (operator + operands' keys) so a projected distance reads back.
internal class VectorDistanceOp(
    private val left: Operand<Vector>,
    private val right: Expression,
    private val metric: VectorMetric,
    private val rightKey: Any,
) : NumericExpr<Double> {
    override val columnType: ColumnType<Double> get() = DoubleColumnType

    override fun toSql(builder: ParamBuilder): String =
        "(${left.toSql(builder)} ${metric.pgOperator()} ${right.toSql(builder)})"

    override fun resultKey(): Any = "(${left.resultKey()} ${metric.pgOperator()} $rightKey)"
}

/**
 * pgvector distance from this vector operand to a query [Vector], as an orderable `Double` — the
 * general form over all [VectorMetric]s. Nearest-neighbour search is an ascending `orderBy` (for every
 * metric, smaller = more similar):
 *
 * ```kotlin
 * Docs.find { orderBy ASC Docs.embedding.distance(query, VectorMetric.COSINE); limit = 5 }
 * ```
 *
 * The query vector binds as a parameter with a `::vector` cast — never string-interpolated. Composes in
 * `where` for a radius filter and in `select(...)` to read the score back. [metric] defaults to [VectorMetric.COSINE],
 * the usual choice for text embeddings. The named aliases below ([euclideanDistance] / [cosineDistance] /
 * [innerProduct]) are sugar over this.
 */
fun Operand<Vector>.distance(query: Vector, metric: VectorMetric = VectorMetric.COSINE): NumericExpr<Double> =
    VectorDistanceOp(this, Value(query), metric, query.toString())

/** pgvector distance between two vector operands (e.g. two columns), over the given [metric]. */
fun Operand<Vector>.distance(other: Operand<Vector>, metric: VectorMetric = VectorMetric.COSINE): NumericExpr<Double> =
    VectorDistanceOp(this, other, metric, other.resultKey())

/** Euclidean (L2) distance `<->` to a query vector. Alias for `distance(query, VectorMetric.EUCLIDEAN)`. */
fun Operand<Vector>.euclideanDistance(query: Vector): NumericExpr<Double> = distance(query, VectorMetric.EUCLIDEAN)

/** Euclidean (L2) distance `<->` between two vector operands. */
fun Operand<Vector>.euclideanDistance(other: Operand<Vector>): NumericExpr<Double> = distance(other, VectorMetric.EUCLIDEAN)

/** Cosine distance `<=>` (`1 - similarity`) to a query vector. Alias for `distance(query, VectorMetric.COSINE)`. */
fun Operand<Vector>.cosineDistance(query: Vector): NumericExpr<Double> = distance(query, VectorMetric.COSINE)

/** Cosine distance `<=>` between two vector operands. */
fun Operand<Vector>.cosineDistance(other: Operand<Vector>): NumericExpr<Double> = distance(other, VectorMetric.COSINE)

/**
 * Negative inner product `<#>` to a query vector. Alias for `distance(query, VectorMetric.DOT)`. pgvector
 * negates the dot product, so the value is ≤ 0 and, like the others, smaller means more similar.
 */
fun Operand<Vector>.innerProduct(query: Vector): NumericExpr<Double> = distance(query, VectorMetric.DOT)

/** Negative inner product `<#>` between two vector operands. */
fun Operand<Vector>.innerProduct(other: Operand<Vector>): NumericExpr<Double> = distance(other, VectorMetric.DOT)
