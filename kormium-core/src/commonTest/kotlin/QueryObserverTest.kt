@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.Catalog
import io.github.kormium.KormiumConfig
import io.github.kormium.QueryEvent
import io.github.kormium.QueryKind
import io.github.kormium.QueryObserver
import io.github.kormium.autocommit
import io.github.kormium.database.Database
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.suspendAutocommit
import io.github.kormium.transaction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object ObsCat : Catalog

/** Unit coverage for the [QueryObserver] seam wired through [autocommit] / [transaction]. */
class QueryObserverTest {

    private fun mock(observer: QueryObserver?, canned: Any?): Database<ObsCat> =
        DatabaseMock().apply {
            config = KormiumConfig(queryObserver = observer)
            result = canned
        }

    @Test
    fun selectIsObservedWithKindRowCountAndBackend() {
        val events = mutableListOf<QueryEvent>()
        val db = mock({ events += it }, listOf(1, 2, 3))
        val rows = db.autocommit { execute("SELECT 1", params = emptyMap(), invalidates = emptyList()) { it.getInt(0) } }
        assertEquals(3, rows.size)
        assertEquals(1, events.size)
        val e = events.single()
        assertEquals(QueryKind.Select, e.kind)
        assertEquals(3L, e.rowCount)
        assertEquals("StandardDialect", e.backend)
        assertEquals("SELECT 1", e.sql)
        assertTrue(e.succeeded)
        assertNull(e.error)
        assertNull(e.sqlState)
        assertTrue(e.durationNanos >= 0)
    }

    @Test
    fun updateReportsAffectedRowCountAndKind() {
        val events = mutableListOf<QueryEvent>()
        val db = mock({ events += it }, 5L)
        val affected = db.transaction { executeUpdate("UPDATE t SET x = :x", params = mapOf("x" to 1), invalidates = emptyList()) }
        assertEquals(5L, affected)
        val e = events.single()
        assertEquals(QueryKind.Update, e.kind)
        assertEquals(5L, e.rowCount)
    }

    @Test
    fun observerNeverSeesParameterValues() {
        val events = mutableListOf<QueryEvent>()
        val db = mock({ events += it }, 1L)
        db.transaction {
            executeUpdate("INSERT INTO t (secret) VALUES (:secret)", params = mapOf("secret" to "hunter2"), invalidates = emptyList())
        }
        // The SQL template is reported verbatim; the value must not appear anywhere on the event.
        assertEquals("INSERT INTO t (secret) VALUES (:secret)", events.single().sql)
        assertEquals(QueryKind.Insert, events.single().kind)
    }

    @Test
    fun nullObserverDoesNotWrapOrCrash() {
        val db = mock(null, listOf(42))
        // No observer configured: the call path must work exactly as before.
        assertEquals(listOf(42), db.autocommit { execute("SELECT 1", params = emptyMap(), invalidates = emptyList()) { it.getInt(0) } })
    }

    @Test
    fun throwingObserverDoesNotBreakTheQuery() {
        val db = mock({ throw RuntimeException("observer boom") }, listOf(7))
        // A misbehaving observer is swallowed; the query result is unaffected.
        assertEquals(listOf(7), db.autocommit { execute("SELECT 1", params = emptyMap(), invalidates = emptyList()) { it.getInt(0) } })
    }

    @Test
    fun suspendPathIsObservedToo() = runTest {
        val events = mutableListOf<QueryEvent>()
        val db: SuspendDatabase<ObsCat> = DatabaseMock().apply {
            config = KormiumConfig(queryObserver = { events += it })
            result = listOf(1, 2)
        }
        val rows = db.suspendAutocommit { execute("SELECT 1", params = emptyMap(), invalidates = emptyList()) { it.getInt(0) } }
        assertEquals(2, rows.size)
        val e = events.single()
        assertEquals(QueryKind.Select, e.kind)
        assertEquals(2L, e.rowCount)
        assertEquals("StandardDialect", e.backend)
    }

    @Test
    fun queryKindClassification() {
        assertEquals(QueryKind.Select, QueryKind.of("  SELECT * FROM t"))
        assertEquals(QueryKind.Select, QueryKind.of("with cte as (select 1) select * from cte"))
        assertEquals(QueryKind.Insert, QueryKind.of("insert into t values (1)"))
        assertEquals(QueryKind.Update, QueryKind.of("UPDATE t SET x=1"))
        assertEquals(QueryKind.Delete, QueryKind.of("DELETE FROM t"))
        assertEquals(QueryKind.Other, QueryKind.of("CREATE TABLE t (id int)"))
        assertEquals(QueryKind.Other, QueryKind.of(""))
    }
}
