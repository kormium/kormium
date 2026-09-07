import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.LockWait
import io.github.kormium.PostgresDialect
import io.github.kormium.RowLock
import io.github.kormium.StandardDialect
import io.github.kormium.Table
import io.github.kormium.UnsupportedByDialectException
import io.github.kormium.eq
import io.github.kormium.forShare
import io.github.kormium.forUpdate
import io.github.kormium.renderPostgresSql
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private object JobCatalog : Catalog

private class JobEntity : Entity() {
    var id by Jobs.id
    var status by Jobs.status
}

private object Jobs : Table<JobCatalog, JobEntity>("jobs", ::JobEntity) {
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
        fun render(block: io.github.kormium.QueryBuilderOf<io.github.kormium.PostgresBackend>.() -> Unit) =
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
            io.github.kormium.Query(lock = RowLock(share = false, wait = LockWait.SkipLocked))
                .toSql(io.github.kormium.ParamBuilder(StandardDialect, io.github.kormium.StandardTypeMapper))
        }
        assertEquals(true, e.message!!.contains("FOR UPDATE SKIP LOCKED"))
    }

    @Test
    fun postgresRendersTheLockItselfIdentically() {
        assertEquals("FOR UPDATE", PostgresDialect.renderRowLock(RowLock()))
        assertEquals("FOR SHARE NOWAIT", PostgresDialect.renderRowLock(RowLock(share = true, wait = LockWait.NoWait)))
    }
}
