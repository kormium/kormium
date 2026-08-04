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
import io.github.kormium.sql.getUUID
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
        // Boxing attribution. Each pair differs only in whether a primitive is boxed, so the
        // gap between the two is the cost of boxing on this platform — on the store side
        // (Array<Any?> vs LongArray) and on the read side (a T? return vs a primitive one,
        // which is what ResultSet.getInt(): Int? forces today).
        report("probeBoxStore500", 20_000) { probeBoxStore() }
        report("probeLongStore500", 20_000) { probeLongStore() }
        report("probeNullableRead500", 20_000) { probeNullableRead() }
        report("probePrimitiveRead500", 20_000) { probePrimitiveRead() }
        report("probeTryCatchRead500", 20_000) { probeTryCatchRead() }
        // Per-row column iteration. mapToDao and hydrate each walk the table's column registry
        // once per row, so a row of 6 columns costs 12 map-entry iterations. These two probes
        // price that walk against the array walk it could be.
        report("probeMapIterate100", 20_000) { probeMapIterate() }
        report("probeArrayIterate100", 20_000) { probeArrayIterate() }
        // UUID reading. Every row of a table with a UUID key pays getString() + Uuid.parse().
        // These price the stdlib parse, a hand-rolled one, the Uuid construction floor, and the
        // UTF-8 decode that produces the String in the first place (what toKString() does in a
        // native driver).
        report("probeUuidGetUuid100", 5_000) { probeUuidGetUuid() }
        report("probeUuidFromLongs100", 5_000) { probeUuidFromLongs() }
        report("probeUtf8Decode100", 5_000) { probeUtf8Decode() }
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

    // ---- UUID probes ----

    private val uuidBytes: ByteArray = SAMPLE_UUID.encodeToByteArray()


    // The production read path: ResultSet.getUUID, fast path plus its validation.
    private val uuidRs = BenchResultSet(1)

    private fun probeUuidGetUuid(): Int {
        var acc = 0
        repeat(100) { acc += uuidRs.getUUID(0).hashCode() }
        return acc
    }


    private fun probeUuidFromLongs(): Int {
        var acc = 0
        repeat(100) { acc += kotlin.uuid.Uuid.fromLongs(seed, seed).hashCode() }
        return acc
    }

    // What toKString() does on a native driver before any parsing can start.
    private fun probeUtf8Decode(): Int {
        var acc = 0
        repeat(100) { acc += uuidBytes.decodeToString().length }
        return acc
    }

    // ---- boxing probes ----

    // Held in fields, not constants, so the values cannot be folded away, and offset well past
    // any small-value box cache so every store is a real allocation (as production ids, amounts
    // and timestamps are).
    private val boxSink = arrayOfNulls<Any?>(8)
    private val longSink = LongArray(8)
    private var seed = 100_000L

    private fun probeBoxStore(): Int {
        val base = seed
        for (i in 0 until 500) boxSink[i and 7] = base + i   // boxes a Long per store
        return 500
    }

    private fun probeLongStore(): Int {
        val base = seed
        for (i in 0 until 500) longSink[i and 7] = base + i  // no box
        return 500
    }

    // Reads go through an interface so the call is virtual and cannot be inlined away —
    // mirroring how core reaches a value through ResultSet / ColumnType.
    private interface IntSource {
        fun nullable(i: Int): Int?
        fun primitive(i: Int): Int
    }

    private class OffsetIntSource(private val offset: Int) : IntSource {
        override fun nullable(i: Int): Int? = i + offset
        override fun primitive(i: Int): Int = i + offset
    }

    private val intSource: IntSource = OffsetIntSource(100_000)

    private fun probeNullableRead(): Int {
        var acc = 0
        for (i in 0 until 500) acc += intSource.nullable(i) ?: 0
        return acc
    }

    private fun probePrimitiveRead(): Int {
        var acc = 0
        for (i in 0 until 500) acc += intSource.primitive(i)
        return acc
    }

    // The table's registry shape: a LinkedHashMap of fieldKey -> column, versus the same
    // columns in a flat array.
    private val iterMap: Map<String, Any> = BenchUsers.getFieldDisplayNames()
    private val iterArray: Array<Any> = BenchUsers.getFieldDisplayNames().values.toTypedArray()

    private fun probeMapIterate(): Int {
        var acc = 0
        repeat(100) {
            for ((name, column) in iterMap) {
                if (name.isNotEmpty()) acc++
                if (column !== iterArray) acc++
            }
        }
        return acc
    }

    private fun probeArrayIterate(): Int {
        var acc = 0
        repeat(100) {
            for (column in iterArray) {
                acc++
                if (column !== iterArray) acc++
            }
        }
        return acc
    }

    // Same reads, each wrapped the way readColumn() wraps every cell.
    private fun probeTryCatchRead(): Int {
        var acc = 0
        for (i in 0 until 500) {
            acc += try {
                intSource.primitive(i)
            } catch (e: Throwable) {
                0
            }
        }
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

