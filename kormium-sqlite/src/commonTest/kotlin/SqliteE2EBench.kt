@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.eq
import io.github.kormium.gtEq
import io.github.kormium.transaction
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * End-to-end benchmark over a REAL driver — the complement to `CorePerfBench` in
 * `kormium-core`, which uses a fake `ResultSet` and therefore measures core only.
 *
 * Everything the fake harness skips is in here: statement preparation, the sqlite3 cinterop
 * calls, `toKString()` UTF-8 decoding on every text cell, and value conversion — on top of
 * the SQL rendering and row hydration core does. SQLite in memory, so there is no network
 * round trip and no disk: what remains is CPU, which is what this effort is about.
 *
 *   ./gradlew :kormium-sqlite:linkBenchReleaseTestMingwX64
 *   ./kormium-sqlite/build/bin/mingwX64/benchReleaseTest/bench.exe --ktest_filter=SqliteE2EBench.benchmark
 */
class SqliteE2EBench {

    @Test
    fun benchmark() {
        val db: Database<E2ECat> = createSqliteDatabase(path = ":memory:") { }

        db.use {
            db.transaction { E2EUsers.execSql(E2E_DDL) }
            seed(db, rows = 100)

            println("BENCH ---- start ----")
            report("e2eSelect100", 2_000) { db.autocommit { E2EUsers.find { where { E2EUsers.age gtEq 0 } } }.size }
            report("e2eSelectOne", 5_000) { if (db.autocommit { E2EUsers.findOne { where { E2EUsers.id eq firstId } } } != null) 1 else 0 }
            report("e2eInsert", 2_000) { insertOne(db) }
            println("BENCH ---- end ----")
        }
    }

    private lateinit var firstId: Uuid

    private fun seed(db: Database<E2ECat>, rows: Int) {
        val users = (0 until rows).map { i ->
            E2EUser().apply {
                id = Uuid.random()
                name = "User number $i"
                age = 20 + i
                email = "user$i@example.com"
                active = i % 2 == 0
                score = 100_000L + i
            }
        }
        firstId = users.first().id
        db.transaction { E2EUsers.insertAll(users) }
    }

    private var counter = 0

    private fun insertOne(db: Database<E2ECat>): Int {
        val user = E2EUser().apply {
            id = Uuid.random()
            name = "Inserted ${counter++}"
            age = 30
            email = "insert@example.com"
            active = true
            score = 999_000L
        }
        db.transaction { E2EUsers.insert(user) }
        return 1
    }

    private fun report(name: String, iterations: Int, block: () -> Int) {
        var sink = 0
        repeat(iterations / 4 + 1) { sink += block() }

        val mark = TimeSource.Monotonic.markNow()
        repeat(iterations) { sink += block() }
        val elapsed = mark.elapsedNow()

        val perOp = elapsed.inWholeNanoseconds.toDouble() / iterations
        val opsPerSec = if (perOp > 0) 1_000_000_000.0 / perOp else 0.0
        println("BENCH $name: ${perOp.toLong()} ns/op, ${opsPerSec.toLong()} ops/s (sink=$sink)")
    }
}

internal val E2E_DDL =
    """CREATE TABLE IF NOT EXISTS "e2e_users" ("id" TEXT NOT NULL, "name" TEXT NOT NULL, """ +
        """"age" INTEGER NOT NULL, "email" TEXT NOT NULL, "active" INTEGER NOT NULL, """ +
        """"score" INTEGER NOT NULL, PRIMARY KEY ("id"))"""

object E2ECat : Catalog

// Same shape as CorePerfBench's table, so the two harnesses are directly comparable.
object E2EUsers : Table<E2ECat, E2EUser>("e2e_users", ::E2EUser) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val age by Column.Int()
    val email by Column.Text()
    val active by Column.Boolean()
    val score by Column.Long()

    init { id; name; age; email; active; score }
}

class E2EUser : Entity() {
    var id by E2EUsers.id
    var name by E2EUsers.name
    var age by E2EUsers.age
    var email by E2EUsers.email
    var active by E2EUsers.active
    var score by E2EUsers.score
}
