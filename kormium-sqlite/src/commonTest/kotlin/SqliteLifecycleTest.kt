import io.github.kormium.Catalog
import io.github.kormium.DatabaseClosedException
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.suspendAutocommit
import io.github.kormium.transaction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private object LifeCat : Catalog

/**
 * Lifecycle contract against a real SQLite backend. On JVM this exercises the shared
 * `JdbcDatabase` path; on native it exercises the libsqlite driver — both must behave the same:
 * idempotent close, `isClosed` reflects state, and use-after-close throws [DatabaseClosedException].
 */
class SqliteLifecycleTest {

    @Test
    fun isClosedAndDoubleClose() {
        val db = createSqliteDatabase(":memory:")
        assertFalse(db.isClosed)
        db.close()
        assertTrue(db.isClosed)
        db.close() // idempotent: must not throw
        assertTrue(db.isClosed)
    }

    @Test
    fun useAfterCloseThrowsDatabaseClosed() {
        val db = createSqliteDatabase(":memory:")
        db.close()
        assertFailsWith<DatabaseClosedException> { db.autocommit { execute("SELECT 1") } }
        assertFailsWith<DatabaseClosedException> { db.transaction { execute("SELECT 1") } }
    }

    @Test
    fun suspendUseAfterCloseThrowsDatabaseClosed() = runTest {
        val db = createSqliteDatabase(":memory:")
        db.close()
        assertFailsWith<DatabaseClosedException> { db.suspendAutocommit { execute("SELECT 1") } }
    }
}
