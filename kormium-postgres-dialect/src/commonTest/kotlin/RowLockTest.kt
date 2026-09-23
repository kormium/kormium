import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.LockStrength
import io.github.kormium.LockWait
import io.github.kormium.PostgresDialect
import io.github.kormium.RowLock
import io.github.kormium.StandardDialect
import io.github.kormium.Table
import io.github.kormium.UnsupportedByDialectException
import io.github.kormium.eq
import io.github.kormium.forKeyShare
import io.github.kormium.forNoKeyUpdate
import io.github.kormium.forShare
import io.github.kormium.forUpdate
import io.github.kormium.renderPostgresSql
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

object JobCatalog : Catalog

class JobEntity : Entity() {
    var id by Jobs.id
    var status by Jobs.status
}

object Jobs : Table<JobCatalog, JobEntity>("jobs", ::JobEntity) {
    val id by Column.Int()
    val status by Column.Text()
}

/**
 * The locking DSL, rendered offline. These queries only compile because the render scope is
 * tagged [io.github.kormium.PostgresBackend]; the same block against a portable scope does not
 * resolve `forUpdate` at all, which is the point of the tag and cannot be asserted at runtime.
 */
class RowLockTest {

    @Test
    fun rendersForUpdateAfterTheLimitClause() {
        val sql = renderPostgresSql<JobCatalog, _> {
            Jobs.find {
                where { Jobs.status eq "ACTIVE" }
                limit = 10
                forUpdate(LockWait.SkipLocked)
            }
        }.sql
        assertEquals(
            """SELECT "id", "status" FROM "jobs" WHERE "status" = :p0 LIMIT 10 FOR UPDATE SKIP LOCKED""",
            sql,
        )
    }

    @Test
    fun rendersEveryLockFlavour() {
        fun render(block: io.github.kormium.SelectQueryBuilderOf<io.github.kormium.PostgresBackend>.() -> Unit) =
            renderPostgresSql<JobCatalog, _> { Jobs.find(block) }.sql.substringAfter(""""jobs" """)

        assertEquals("FOR UPDATE", render { forUpdate() })
        assertEquals("FOR UPDATE NOWAIT", render { forUpdate(LockWait.NoWait) })
        assertEquals("FOR UPDATE SKIP LOCKED", render { forUpdate(LockWait.SkipLocked) })
        assertEquals("FOR SHARE", render { forShare() })
        assertEquals("FOR SHARE SKIP LOCKED", render { forShare(LockWait.SkipLocked) })
    }

    @Test
    fun findOneLocksTheSingleRowItReads() {
        val sql = renderPostgresSql<JobCatalog, _> { Jobs.findOne { where { Jobs.id eq 1 }; forUpdate() } }.sql
        assertEquals("""SELECT "id", "status" FROM "jobs" WHERE "id" = :p0 LIMIT 1 FOR UPDATE""", sql)
    }

    @Test
    fun aDialectThatCannotLockFailsLoudlyInsteadOfDroppingTheClause() {
        // Reachable only by bypassing the typed DSL (a Query built by hand, as a dialect module
        // or a DelicateKormiumApi caller might) — the guarantee the type system cannot give.
        val e = assertFailsWith<UnsupportedByDialectException> {
            io.github.kormium.Query(lock = RowLock(wait = LockWait.SkipLocked))
                .toSql(io.github.kormium.ParamBuilder(StandardDialect, io.github.kormium.StandardTypeMapper))
        }
        assertEquals(true, e.message!!.contains("FOR UPDATE SKIP LOCKED"))
    }

    @Test
    fun rendersThePostgresOnlyWeakerStrengths() {
        fun render(block: io.github.kormium.SelectQueryBuilderOf<io.github.kormium.PostgresBackend>.() -> Unit) =
            renderPostgresSql<JobCatalog, _> { Jobs.find(block) }.sql.substringAfter(""""jobs" """)

        assertEquals("FOR NO KEY UPDATE", render { forNoKeyUpdate() })
        assertEquals("FOR NO KEY UPDATE NOWAIT", render { forNoKeyUpdate(LockWait.NoWait) })
        assertEquals("FOR KEY SHARE", render { forKeyShare() })
        assertEquals("FOR KEY SHARE SKIP LOCKED", render { forKeyShare(LockWait.SkipLocked) })
    }

    @Test
    fun aLockIsRejectedOnStatementsThatRenderOnlyTheWhereClause() {
        // The DSL cannot express it (only the find/findOne builder carries a lock), so this is
        // the Query-value form — which any code can build, with no tag and no opt-in.
        val locked = io.github.kormium.Query(lock = RowLock(wait = LockWait.SkipLocked))
        for (statement in listOf<Pair<String, () -> Unit>>(
            "count" to { renderPostgresSql<JobCatalog, _> { Jobs.count(locked) } },
            "deleteWhere" to { renderPostgresSql<JobCatalog, _> { Jobs.deleteWhere(locked) } },
            "update" to { renderPostgresSql<JobCatalog, _> { Jobs.update(JobEntity().apply { id = 1 }, locked) } },
        )) {
            val e = assertFailsWith<IllegalArgumentException>("$statement accepted a row lock") {
                statement.second()
            }
            assertEquals(true, e.message!!.contains("silently dropped"), "unexpected: ${e.message}")
        }
    }

    @Test
    fun postgresRendersTheLockItselfIdentically() {
        assertEquals("FOR UPDATE", PostgresDialect.renderRowLock(RowLock()))
        assertEquals(
            "FOR SHARE NOWAIT",
            PostgresDialect.renderRowLock(RowLock(LockStrength.Share, LockWait.NoWait)),
        )
        assertEquals("FOR KEY SHARE", PostgresDialect.renderRowLock(RowLock(LockStrength.KeyShare)))
    }
}
