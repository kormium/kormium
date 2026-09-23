@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.LockWait
import io.github.kormium.BackendDatabase
import io.github.kormium.PostgresBackend
import io.github.kormium.QueryException
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.eq
import io.github.kormium.forUpdate
import io.github.kormium.transaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory

object LockCatalog : Catalog

class LockJob : Entity() {
    var id by LockJobs.id
    var status by LockJobs.status
}

object LockJobs : Table<LockCatalog, LockJob>("lock_jobs", ::LockJob) {
    val id by Column.Int().primaryKey()
    val status by Column.Text()

    init {
        id; status
    }
}

/**
 * What the rendering tests cannot show: that the lock actually keeps two transactions apart.
 * Two concurrent workers claim from the same queue with `SKIP LOCKED` and must come away with
 * disjoint rows; a third asks for a row someone else holds with `NOWAIT` and must be refused
 * rather than left waiting.
 *
 * Needs Docker (Testcontainers); skipped where there is none.
 */
class RowLockingIntegrationTest {

    @Test
    fun skipLockedHandsConcurrentWorkersDisjointBatches() = withDatabase { db ->
        seed(db, count = 6)

        val workerAClaimed = CountDownLatch(1)
        val releaseWorkerA = CountDownLatch(1)
        val claimedByA = AtomicReference<List<Int>>(emptyList())
        val failure = AtomicReference<Throwable?>(null)

        // Worker A claims two rows and holds its transaction open, so the locks stay taken.
        val a = thread(name = "worker-A") {
            try {
                db.transaction {
                    claimedByA.set(claimBatch(size = 2))
                    workerAClaimed.countDown()
                    check(releaseWorkerA.await(30, TimeUnit.SECONDS)) { "worker B never finished" }
                }
            } catch (t: Throwable) {
                failure.set(t)
                workerAClaimed.countDown()
            }
        }

        assertTrue(workerAClaimed.await(30, TimeUnit.SECONDS), "worker A never claimed")
        failure.get()?.let { throw it }

        // Worker B runs while A still holds its locks: it must skip them, not block and not repeat.
        val claimedByB = db.transaction { claimBatch(size = 2) }
        releaseWorkerA.countDown()
        a.join()
        failure.get()?.let { throw it }

        assertEquals(2, claimedByA.get().size)
        assertEquals(2, claimedByB.size)
        assertEquals(
            emptyList(),
            claimedByA.get().intersect(claimedByB.toSet()).toList(),
            "SKIP LOCKED handed the same job to both workers",
        )
    }

    @Test
    fun nowaitFailsInsteadOfWaitingForALockedRow() = withDatabase { db ->
        seed(db, count = 1)
        val id = db.autocommit { LockJobs.find { }.first().id }

        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(name = "lock-holder") {
            db.transaction {
                LockJobs.findOne { where { LockJobs.id eq id }; forUpdate() }
                locked.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "the NOWAIT probe never finished" }
            }
        }
        assertTrue(locked.await(30, TimeUnit.SECONDS), "the row was never locked")

        val e = assertFailsWith<QueryException> {
            db.transaction {
                LockJobs.findOne { where { LockJobs.id eq id }; forUpdate(LockWait.NoWait) }
            }
        }
        release.countDown()
        holder.join()

        // 55P03 = lock_not_available. Kormium has no dedicated subtype for it yet, so this is the
        // generic QueryException carrying the SQLSTATE.
        assertEquals("55P03", e.sqlState)
    }

    @Test
    fun aLockOutsideATransactionIsRejectedBeforeItReachesTheServer() = withDatabase { db ->
        val e = assertFailsWith<IllegalStateException> {
            db.autocommit { LockJobs.find { forUpdate() } }
        }
        assertTrue(e.message!!.contains("requires transaction"), "unexpected message: ${e.message}")
    }

    // ---- helpers ----

    /** Claims [size] unlocked jobs and marks them RUNNING; must run inside a transaction. */
    private fun io.github.kormium.ScopeOf<LockCatalog, PostgresBackend>.claimBatch(size: Int): List<Int> {
        val batch = LockJobs.find {
            where { LockJobs.status eq "ACTIVE" }
            orderBy ASC LockJobs.id
            limit = size
            forUpdate(LockWait.SkipLocked)
        }
        batch.forEach { job ->
            LockJobs.update(LockJob().apply { status = "RUNNING" }) { where { LockJobs.id eq job.id } }
        }
        return batch.map { it.id }
    }

    /**
     * One pool per test, released when it ends. The container is shared across the whole module, so
     * a leaked 4-connection pool per test would eventually exhaust `max_connections` in some later
     * suite rather than here.
     */
    private fun <R> withDatabase(block: (BackendDatabase<LockCatalog, PostgresBackend>) -> R): R {
        assumeDockerAvailable()
        return ItDatabase.newDriver(poolSize = 4).use { block(it) }
    }

    private fun seed(db: BackendDatabase<LockCatalog, PostgresBackend>, count: Int) {
        db.autocommit {
            executeUpdate(
                """CREATE TABLE IF NOT EXISTS "lock_jobs" ("id" integer PRIMARY KEY, "status" text NOT NULL)""",
                params = emptyMap(),
                invalidates = emptyList(),
            )
            executeUpdate("""DELETE FROM "lock_jobs"""", params = emptyMap(), invalidates = listOf(LockJobs))
        }
        db.transaction {
            LockJobs.insertAll((1..count).map { n -> LockJob().apply { id = n; status = "ACTIVE" } })
        }
    }

    private fun assumeDockerAvailable() =
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
}
