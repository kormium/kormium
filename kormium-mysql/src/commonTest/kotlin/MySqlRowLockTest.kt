import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.LockWait
import io.github.kormium.MySqlDialect
import io.github.kormium.RowLock
import io.github.kormium.SelectQueryBuilderOf
import io.github.kormium.MySqlBackend
import io.github.kormium.Table
import io.github.kormium.eq
import io.github.kormium.forShare
import io.github.kormium.forUpdate
import io.github.kormium.renderMySqlSql
import kotlin.test.Test
import kotlin.test.assertEquals

private object MyQueueCatalog : Catalog

private class MyJobEntity : Entity() {
    var id by MyJobs.id
    var status by MyJobs.status
}

private object MyJobs : Table<MyQueueCatalog, MyJobEntity>("jobs", ::MyJobEntity) {
    val id by Column.Int()
    val status by Column.Text()
}

/**
 * The locking DSL on MySQL, rendered offline. Mirrors the Postgres suite: same DSL, same core
 * capability tag, only the dialect's spelling differs — and here it does not, which is the point
 * of rendering both. What MySQL does not share is version tolerance; see
 * [MySqlDialect.renderRowLock].
 */
class MySqlRowLockTest {

    private fun tail(block: SelectQueryBuilderOf<MySqlBackend>.() -> Unit) =
        renderMySqlSql<MyQueueCatalog, _> { MyJobs.find(block) }.sql.substringAfter("`jobs` ")

    @Test
    fun rendersTheLockAfterLimitWithBacktickedIdentifiers() {
        val sql = renderMySqlSql<MyQueueCatalog, _> {
            MyJobs.find {
                where { MyJobs.status eq "ACTIVE" }
                limit = 10
                forUpdate(LockWait.SkipLocked)
            }
        }.sql
        assertEquals(
            "SELECT `id`, `status` FROM `jobs` WHERE `status` = :p0 LIMIT 10 FOR UPDATE SKIP LOCKED",
            sql,
        )
    }

    @Test
    fun rendersEveryLockFlavour() {
        assertEquals("FOR UPDATE", tail { forUpdate() })
        assertEquals("FOR UPDATE NOWAIT", tail { forUpdate(LockWait.NoWait) })
        assertEquals("FOR UPDATE SKIP LOCKED", tail { forUpdate(LockWait.SkipLocked) })
        assertEquals("FOR SHARE", tail { forShare() })
        assertEquals("FOR SHARE NOWAIT", tail { forShare(LockWait.NoWait) })
    }

    @Test
    fun offsetSentinelStillPrecedesTheLock() {
        // MySQL needs a LIMIT before an OFFSET; the lock has to come after that whole tail.
        val sql = renderMySqlSql<MyQueueCatalog, _> { MyJobs.find { offset = 5; forUpdate() } }.sql
        assertEquals("SELECT `id`, `status` FROM `jobs` LIMIT 18446744073709551615 OFFSET 5 FOR UPDATE", sql)
    }

    @Test
    fun dialectRendersTheLockItself() {
        assertEquals("FOR UPDATE", MySqlDialect.renderRowLock(RowLock()))
        assertEquals("FOR SHARE SKIP LOCKED", MySqlDialect.renderRowLock(RowLock(share = true, wait = LockWait.SkipLocked)))
    }
}
