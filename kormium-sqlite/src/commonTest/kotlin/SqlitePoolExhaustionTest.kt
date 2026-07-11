@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.PoolExhaustedException
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import io.github.kormium.transaction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Saturated-pool contract (#36): when every pooled connection is busy, an acquire fails with
 * [PoolExhaustedException] after `acquireTimeout` instead of hanging forever. `poolSize = 1` plus a
 * *nested* borrow makes exhaustion deterministic without threads: the outer transaction pins the
 * only connection, so the inner borrow can never succeed. On JVM this exercises the HikariCP
 * checkout timeout (250 ms floor) wrapped into the portable exception; on native, the
 * Channel-pool timeout — the same code shape the Postgres and MySQL native drivers share.
 */
class SqlitePoolExhaustionTest {

    @Test
    fun blockingAcquireTimesOutWithClearError() {
        createSqliteDatabase(":memory:", poolSize = 1, acquireTimeout = 300.milliseconds).use { db ->
            val e = assertFailsWith<PoolExhaustedException> {
                db.transaction {
                    // The only connection is pinned by the enclosing transaction.
                    db.autocommit { }
                }
            }
            assertTrue("poolSize = 1" in (e.message ?: ""), "message should name the pool size: ${e.message}")
            assertTrue("pool exhausted" in (e.message ?: ""), "message should say what happened: ${e.message}")
        }
    }

    @Test
    fun suspendAcquireTimesOutWithClearError() = runTest {
        createSqliteDatabase(":memory:", poolSize = 1, acquireTimeout = 300.milliseconds).use { db ->
            assertFailsWith<PoolExhaustedException> {
                db.suspendTransaction {
                    db.suspendAutocommit { }
                }
            }
        }
    }

    @Test
    fun acquireSucceedsAgainAfterTheHolderReleases() {
        createSqliteDatabase(":memory:", poolSize = 1, acquireTimeout = 300.milliseconds).use { db ->
            runCatching { db.transaction { db.autocommit { } } } // exhaust once
            // The pool must be fully usable afterwards — the timed-out borrow must not leak.
            db.autocommit { executeUpdate("CREATE TABLE t(x INTEGER)", params = emptyMap(), invalidates = emptyList()) }
        }
    }
}
