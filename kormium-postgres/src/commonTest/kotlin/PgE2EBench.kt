@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.eq
import io.github.kormium.gtEq
import io.github.kormium.transaction
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** Reads a benchmark environment variable; `System.getenv` on JVM, `getenv` on native. */
internal expect fun pgBenchEnv(name: String): String?

/**
 * End-to-end PostgreSQL benchmark, compiled for BOTH the JVM and native targets from this one
 * source — so "Kormium on JVM" and "Kormium on Kotlin/Native" run identical operations against
 * the same database, and the two columns can honestly be compared.
 *
 * The JVM side goes through pgjdbc + HikariCP, the native side through libpq; that difference
 * is the point, not a flaw — it is what each platform actually ships.
 *
 * Unlike the SQLite benchmark (in-memory, pure CPU), every operation here crosses a socket, so
 * a round trip is included in each number. That is what a real application pays.
 *
 * Skipped unless KORMIUM_DB_HOST is set. Run with a PostgreSQL on hand:
 *
 *   ./gradlew :kormium-postgres:jvmTest --tests "PgE2EBench" -i
 *   ./gradlew :kormium-postgres:linkBenchReleaseTestMingwX64
 *   ./kormium-postgres/build/bin/mingwX64/benchReleaseTest/bench.exe --ktest_filter=PgE2EBench.benchmark
 */
class PgE2EBench {

    @Test
    fun benchmark() {
        if (pgBenchEnv("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping the PostgreSQL benchmark")
            return
        }

        val db: Database<PgBenchCat> = createDatabase(
            host = pgBenchEnv("KORMIUM_DB_HOST") ?: "localhost",
            port = pgBenchEnv("KORMIUM_DB_PORT")?.toInt() ?: 5432,
            database = pgBenchEnv("KORMIUM_DB_NAME") ?: "postgres",
            user = pgBenchEnv("KORMIUM_DB_USER") ?: "postgres",
            password = pgBenchEnv("KORMIUM_DB_PASSWORD") ?: "password",
            poolSize = 1,
        )

        db.use {
            db.transaction { PgBenchUsers.execSql(PG_BENCH_DDL) }
            // Start from a known row count so a re-run does not widen the 100-row read.
            db.transaction { PgBenchUsers.deleteWhere(Query()) }
            seed(db, rows = 100)

            println("BENCH ---- start ----")
            report("pgSelect100", 500) { db.autocommit { PgBenchUsers.find { where { PgBenchUsers.age gtEq 0 } } }.size }
            report("pgSelectOne", 1_000) {
                if (db.autocommit { PgBenchUsers.findOne { where { PgBenchUsers.id eq firstId } } } != null) 1 else 0
            }
            report("pgInsert", 500) { insertOne(db) }
            println("BENCH ---- end ----")

            db.transaction { PgBenchUsers.deleteWhere(Query()) }
        }
    }

    private lateinit var firstId: Uuid

    private fun seed(db: Database<PgBenchCat>, rows: Int) {
        val users = (0 until rows).map { i ->
            PgBenchUser().apply {
                id = Uuid.random()
                name = "User number $i"
                age = 20 + i
                email = "user$i@example.com"
                active = i % 2 == 0
                score = 100_000L + i
            }
        }
        firstId = users.first().id
        db.transaction { PgBenchUsers.insertAll(users) }
    }

    private var counter = 0

    private fun insertOne(db: Database<PgBenchCat>): Int {
        val user = PgBenchUser().apply {
            id = Uuid.random()
            name = "Inserted ${counter++}"
            age = 30
            email = "insert@example.com"
            active = true
            score = 999_000L
        }
        db.transaction { PgBenchUsers.insert(user) }
        return 1
    }

    private fun report(name: String, iterations: Int, block: () -> Int) {
        var sink = 0
        // Warm up generously: on the JVM this has to be enough for the JIT to compile and
        // settle, or the comparison against Native measures the interpreter instead.
        repeat(maxOf(iterations, WARMUP_MIN)) { sink += block() }

        // Best of several measured rounds, in one process, so a stray GC pause or scheduling
        // hiccup in a single round does not decide the result.
        var best = Long.MAX_VALUE
        repeat(MEASURED_ROUNDS) {
            val mark = TimeSource.Monotonic.markNow()
            repeat(iterations) { sink += block() }
            val elapsed = mark.elapsedNow().inWholeNanoseconds
            if (elapsed < best) best = elapsed
        }

        val perOp = best.toDouble() / iterations
        val opsPerSec = if (perOp > 0) 1_000_000_000.0 / perOp else 0.0
        println("BENCH $name: ${perOp.toLong()} ns/op, ${opsPerSec.toLong()} ops/s (sink=$sink)")
    }

    private companion object {
        const val WARMUP_MIN = 1_000
        const val MEASURED_ROUNDS = 3
    }
}

internal val PG_BENCH_DDL =
    """CREATE TABLE IF NOT EXISTS "pg_bench_users" ("id" uuid PRIMARY KEY, "name" text NOT NULL, """ +
        """"age" integer NOT NULL, "email" text NOT NULL, "active" boolean NOT NULL, """ +
        """"score" bigint NOT NULL)"""

object PgBenchCat : Catalog

// Same shape as the SQLite benchmark's table, so the two backends are comparable too.
object PgBenchUsers : Table<PgBenchCat, PgBenchUser>("pg_bench_users", ::PgBenchUser) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val age by Column.Int()
    val email by Column.Text()
    val active by Column.Boolean()
    val score by Column.Long()

    init { id; name; age; email; active; score }
}

class PgBenchUser : Entity() {
    var id by PgBenchUsers.id
    var name by PgBenchUsers.name
    var age by PgBenchUsers.age
    var email by PgBenchUsers.email
    var active by PgBenchUsers.active
    var score by PgBenchUsers.score
}
