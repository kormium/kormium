import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Query
import io.github.kormium.SqlExecutor
import io.github.kormium.SqlParameterSource
import io.github.kormium.StandardDialect
import io.github.kormium.StandardTypeMapper
import io.github.kormium.Table
import io.github.kormium.and
import io.github.kormium.eq
import io.github.kormium.gtEq
import io.github.kormium.resultset.ResultSet
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * TEMPORARY measurement harness — CPU-only, no database, no driver, no I/O.
 *
 * It isolates the work Kormium does per query and per row (SQL rendering, result mapping,
 * entity field access) so the identical code can be timed on JVM and Kotlin/Native and the
 * two compared. Not a correctness test: it only asserts that the work ran.
 *
 *   ./gradlew :kormium-core:jvmTest     --tests "CorePerfBench"
 *   ./gradlew :kormium-core:mingwX64Test --tests "CorePerfBench"
 */
class CorePerfBench {

    @Test
    fun benchmark() {
        println("BENCH ---- start ----")
        report("renderSelect", 50_000) { renderSelect() }
        report("renderInsert", 50_000) { renderInsert() }
        report("hydrate100", 5_000) { hydrate(100) }
        report("readFields100", 20_000) { readFields(preloaded) }
        // Attribution probes: the same 500 reads done against bare storage, to separate the
        // cost of the String-keyed HashMap from everything layered on top of it (the logger
        // lambda allocated per accessor call). These are the floors the accessors could reach.
        report("probeMapGet500", 20_000) { probeMapGet() }
        report("probeArrayGet500", 20_000) { probeArrayGet() }
        // Hydration-side floors, per 100 rows: building the field map the way mapToDao does,
        // and the UUID text->value parse it runs per row. Separates "collections" from "parsing".
        report("probeMapBuild100", 5_000) { probeMapBuild() }
        report("probeUuidParse100", 5_000) { probeUuidParse() }
        println("BENCH ---- end ----")
    }

    private val probeKeys = arrayOf("name", "age", "email", "active", "score")
    private val probeMap: HashMap<String, Any?> = HashMap<String, Any?>().apply {
        put("id", SAMPLE_UUID); put("name", "Ada Lovelace"); put("age", 36)
        put("email", "ada@example.com"); put("active", true); put("score", 4242L)
    }
    private val probeArray: Array<Any?> =
        arrayOf(SAMPLE_UUID, "Ada Lovelace", 36, "ada@example.com", true, 4242L)

    private fun probeMapGet(): Int {
        var acc = 0
        repeat(100) { for (k in probeKeys) acc += if (probeMap[k] != null) 1 else 0 }
        return acc
    }

    private fun probeArrayGet(): Int {
        var acc = 0
        repeat(100) { for (i in 1..5) acc += if (probeArray[i] != null) 1 else 0 }
        return acc
    }

    private val buildKeys = arrayOf("id", "name", "age", "email", "active", "score")

    // Mirrors mapToDao's per-row allocation: a presized HashMap filled with 6 keyed values.
    private fun probeMapBuild(): Int {
        var acc = 0
        repeat(100) {
            val m = HashMap<String, Any?>(12)
            for (i in buildKeys.indices) m[buildKeys[i]] = probeArray[i]
            acc += m.size
        }
        return acc
    }

    private fun probeUuidParse(): Int {
        var acc = 0
        repeat(100) { acc += kotlin.uuid.Uuid.parse(SAMPLE_UUID).hashCode() }
        return acc
    }

    // ---- workloads ----

    private fun renderSelect(): Int {
        val (sql, params) = BenchUsers.selectSql(sampleQuery, StandardDialect, StandardTypeMapper)
        return sql.length + params.size
    }

    private fun renderInsert(): Int {
        val (sql, params) = BenchUsers.insertSql(sampleEntity, StandardDialect, StandardTypeMapper, false)
        return sql.length + params.size
    }

    private fun hydrate(rows: Int): Int = BenchUsers.select(sampleQuery, BenchExecutor(rows)).size

    private fun readFields(users: List<BenchUser>): Int {
        var acc = 0
        for (u in users) {
            acc += u.name.length + u.age + u.email.length + u.score.toInt() + if (u.active) 1 else 0
        }
        return acc
    }

    private val preloaded: List<BenchUser> by lazy { BenchUsers.select(sampleQuery, BenchExecutor(100)) }

    private val sampleQuery = Query(
        whereExpression = (BenchUsers.age gtEq 18) and (BenchUsers.active eq true),
        limit = 50u,
    )

    private val sampleEntity = BenchUser().apply {
        id = kotlin.uuid.Uuid.parse(SAMPLE_UUID)
        name = "Ada Lovelace"
        age = 36
        email = "ada@example.com"
        active = true
        score = 4242L
    }

    // ---- harness ----

    private fun report(name: String, iterations: Int, block: () -> Int) {
        var sink = 0
        // Warm up: on JVM this lets the JIT compile the path; on Native it primes caches.
        repeat(iterations / 4 + 1) { sink += block() }

        val mark = TimeSource.Monotonic.markNow()
        repeat(iterations) { sink += block() }
        val elapsed = mark.elapsedNow()

        val perOp = elapsed.inWholeNanoseconds.toDouble() / iterations
        val opsPerSec = if (perOp > 0) 1_000_000_000.0 / perOp else 0.0
        println("BENCH $name: ${perOp.toLong()} ns/op, ${opsPerSec.toLong()} ops/s (sink=$sink)")
    }
}

private const val SAMPLE_UUID = "00000000-0000-4000-8000-000000000001"

object BenchApp : Catalog

object BenchUsers : Table<BenchApp, BenchUser>("users", ::BenchUser) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val age by Column.Int()
    val email by Column.Text()
    val active by Column.Boolean()
    val score by Column.Long()
}

class BenchUser : Entity() {
    var id by BenchUsers.id
    var name by BenchUsers.name
    var age by BenchUsers.age
    var email by BenchUsers.email
    var active by BenchUsers.active
    var score by BenchUsers.score
}

/**
 * A result set of [rows] identical rows shaped like [BenchUsers]. Values are returned by
 * column index, matching the positional select the table renders.
 */
private class BenchResultSet(private val rows: Int) : ResultSet {
    override val columns: Array<String> = arrayOf("id", "name", "age", "email", "active", "score")
    private var cursor = -1

    override fun next(): Boolean {
        cursor++
        return cursor < rows
    }

    override fun getString(columnIndex: Int): String? = when (columnIndex) {
        0 -> SAMPLE_UUID
        1 -> "Ada Lovelace"
        3 -> "ada@example.com"
        else -> null
    }

    override fun getBoolean(columnIndex: Int): Boolean = true
    override fun getShort(columnIndex: Int): Short = 1
    override fun getInt(columnIndex: Int): Int = 36
    override fun getLong(columnIndex: Int): Long = 4242L
    override fun getFloat(columnIndex: Int): Float = 1.5f
    override fun getDouble(columnIndex: Int): Double = 1.5
    override fun getBytes(columnIndex: Int): ByteArray? = null
    override fun getDate(columnIndex: Int): LocalDate? = null
    override fun getTime(columnIndex: Int): LocalTime? = null
    override fun getLocalDateTime(columnIndex: Int): LocalDateTime? = null
    override fun getInstant(columnIndex: Int): Instant? = null
}

/** Feeds [rows] canned rows through the handler, mimicking a driver with the result buffered. */
private class BenchExecutor(private val rows: Int) : SqlExecutor {
    override val dialect = StandardDialect
    override val typeMapper = StandardTypeMapper

    override fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> {
        val rs = BenchResultSet(rows)
        val out = ArrayList<T>(rows)
        while (rs.next()) out.add(handler(rs))
        return out
    }

    override fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        execute(sql, emptyMap(), handler)

    override fun execute(sql: String, namedParameters: Map<String, Any?>): Long = 0L
    override fun execute(sql: String, paramSource: SqlParameterSource): Long = 0L
    override fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long = 0L
}
