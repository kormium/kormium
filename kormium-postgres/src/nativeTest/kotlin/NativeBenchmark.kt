@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.decimal.Decimal
import io.github.kormium.decimal.decimal
import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.eq
import io.github.kormium.transaction
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
private fun benchEnv(name: String): String? = getenv(name)?.toKString()

private fun benchDriver(poolSize: Int): Database<BenchCatalog> = createDatabase(
    host = benchEnv("KORMIUM_DB_HOST") ?: "localhost",
    port = benchEnv("KORMIUM_DB_PORT")?.toInt() ?: 5432,
    database = benchEnv("KORMIUM_DB_NAME") ?: "postgres",
    user = benchEnv("KORMIUM_DB_USER") ?: "postgres",
    password = benchEnv("KORMIUM_DB_PASSWORD") ?: "password",
    poolSize = poolSize,
)

object BenchCatalog : Catalog

class BenchRow : Entity() {
    var id by BenchTable.id
    var name by BenchTable.name
    var amount by BenchTable.amount
}

object BenchTable : Table<BenchCatalog, BenchRow>("cmp_bench", ::BenchRow) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val amount by Column.decimal()

    init { id; name; amount }
}

// Workload shape — keep in sync with the JVM ComparisonBenchmark in `benchmarks`.
private const val BULK_ROWS = 100
private const val BATCH_SIZE = 50
private const val UPDATE_ROWS = 1024

private val benchSeedId = Uuid.random()
private val updateIds = List(UPDATE_ROWS) { Uuid.random() }

private enum class Op { FIND_BY_ID, SELECT_WHERE, SELECT_MANY, INSERT, BATCH_INSERT, UPDATE_BY_ID }

private fun newRow(rowName: String) =
    BenchRow().apply { id = Uuid.random(); name = rowName; amount = Decimal.of(1) }

private fun runOp(db: Database<BenchCatalog>, op: Op) {
    when (op) {
        Op.FIND_BY_ID -> db.autocommit { BenchTable.findOne { where { BenchTable.id eq benchSeedId } } }
        Op.SELECT_WHERE -> db.autocommit { BenchTable.find(Query(BenchTable.name eq "seed")) }
        Op.SELECT_MANY -> db.autocommit { BenchTable.find(Query(BenchTable.name eq "bulk")) }
        Op.INSERT -> db.transaction { BenchTable.insert(newRow("x")) }
        Op.BATCH_INSERT -> db.transaction { BenchTable.insertAll(List(BATCH_SIZE) { newRow("x") }) }
        Op.UPDATE_BY_ID -> db.transaction {
            BenchTable.update(
            BenchRow().apply { amount = Decimal.of(2) },
            Query(BenchTable.id eq updateIds[Random.nextInt(UPDATE_ROWS)]),
        )
        }
    }
}

/**
 * Native counterpart to the JVM JMH ComparisonBenchmark: same six operations and seeding,
 * 8 worker threads, against the Postgres given by KORMIUM_DB_* env vars. Opt-in via
 * KORM_BENCH so it never runs in the normal test suite. KORM_BENCH_OPS overrides the
 * per-thread op count (default 5000) for quick smoke runs. Prints one machine-readable
 * `KORM_NATIVE_RESULT op=ops_per_sec ...` line that benchmarks/run.sh parses.
 */
class NativeBenchmark {

    @Test
    fun runNativeBenchmark() {
        if (benchEnv("KORM_BENCH") == null) {
            println("KORM_BENCH not set — skipping native benchmark")
            return
        }
        val driver = benchDriver(poolSize = 8)
        driver.transaction {
            BenchTable.execSql("DROP TABLE IF EXISTS \"cmp_bench\"")
            BenchTable.execSql(benchDdl)
            executeUpdate("""CREATE INDEX IF NOT EXISTS cmp_bench_name_idx ON "public"."cmp_bench" ("name")""", params = emptyMap(), invalidates = emptyList())
        }

        val threads = 8
        val ops = benchEnv("KORM_BENCH_OPS")?.toInt() ?: 5_000
        val results = listOf(
            "findById" to Op.FIND_BY_ID,
            "selectWhere" to Op.SELECT_WHERE,
            "selectMany" to Op.SELECT_MANY,
            "insert" to Op.INSERT,
            // Batches are ~50x slower per op; scale the count down to keep the run short.
            "batchInsert" to Op.BATCH_INSERT,
            "updateById" to Op.UPDATE_BY_ID,
        ).map { (name, op) ->
            val perThread = if (op == Op.BATCH_INSERT) (ops / 25).coerceAtLeast(10) else ops
            name to bench(driver, threads, perThread, op)
        }

        println("KORM_NATIVE_RESULT " + results.joinToString(" ") { (name, opsSec) -> "$name=${opsSec.toInt()}" })
        driver.close()
    }

    /** Resets the table, warms up, then runs [opsPerThread] ops on [threads] workers; returns ops/s. */
    private fun bench(driver: Database<BenchCatalog>, threads: Int, opsPerThread: Int, op: Op): Double {
        reset(driver)
        repeat(minOf(2_000, opsPerThread * 2)) { runOp(driver, op) } // warm up
        reset(driver)
        val mark = TimeSource.Monotonic.markNow()
        val workers = List(threads) { Worker.start() }
        val futures = workers.map { worker ->
            worker.execute(TransferMode.SAFE, { Triple(driver, opsPerThread, op) }) { (db, n, o) ->
                repeat(n) { runOp(db, o) }
            }
        }
        futures.forEach { it.result }
        val elapsedMs = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        workers.forEach { it.requestTermination().result }
        return threads.toLong() * opsPerThread * 1000.0 / elapsedMs
    }

    /** Same per-iteration state as the JVM harness: seed row, bulk rows, update targets. */
    private fun reset(driver: Database<BenchCatalog>) {
        driver.transaction {
            executeUpdate("""TRUNCATE "public"."cmp_bench"""", params = emptyMap(), invalidates = emptyList())
            BenchTable.insert(BenchRow().apply { id = benchSeedId; name = "seed"; amount = Decimal.of(1) })
            BenchTable.insertAll(List(BULK_ROWS) { newRow("bulk") })
            BenchTable.insertAll(updateIds.map { rowId ->
                BenchRow().apply { id = rowId; name = "upd"; amount = Decimal.of(1) }
            })
        }
    }
}

private val benchDdl = """CREATE TABLE IF NOT EXISTS "cmp_bench" ("id" uuid NOT NULL, "name" text NOT NULL, "amount" numeric NOT NULL, PRIMARY KEY ("id"))"""
