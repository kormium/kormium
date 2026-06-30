import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.KormiumConfig
import io.github.kormium.QueryEvent
import io.github.kormium.QueryKind
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.UniqueViolationException
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.eq
import io.github.kormium.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * End-to-end coverage of [KormiumConfig.queryObserver] against a real SQLite backend: the hook
 * sees DSL operations and raw SQL, reports kind/row-count/duration, and on a constraint failure
 * carries the error and its mapped code — all without wrapping repository methods.
 */
class SqliteQueryObserverTest {

    private val events = mutableListOf<QueryEvent>()
    private val db: Database<ObsCat> = createSqliteDatabase(
        ":memory:",
        config = KormiumConfig(queryObserver = { events += it }),
    )

    @Test
    fun observesInsertAndSelectThroughDsl() {
        val id = Uuid.random()
        db.transaction {
            ObsItems.execSql(obsItemsDdl)
            ObsItems.insert(ObsItem().apply { this.id = id; name = "a" })
        }
        events.clear()
        val found = db.autocommit { ObsItems.findOne { where { ObsItems.id eq id } } }
        assertEquals("a", found?.name)

        val select = events.single { it.kind == QueryKind.Select }
        assertEquals("SqliteDialect", select.backend)
        assertEquals(1L, select.rowCount)
        assertTrue(select.succeeded)
        assertNull(select.error)
        assertTrue(select.durationNanos >= 0)
        assertTrue("select" in select.sql.lowercase())
    }

    @Test
    fun observesRawExecute() {
        db.transaction { ObsItems.execSql(obsItemsDdl) }
        events.clear()
        db.autocommit { execute("SELECT 1") }
        assertTrue(events.any { it.kind == QueryKind.Select && it.sql == "SELECT 1" })
    }

    @Test
    fun failedStatementIsObservedWithErrorAndSqlState() {
        val id = Uuid.random()
        db.transaction {
            ObsItems.execSql(obsItemsDdl)
            ObsItems.insert(ObsItem().apply { this.id = id; name = "dup" })
        }
        events.clear()
        assertFailsWith<UniqueViolationException> {
            db.transaction { ObsItems.insert(ObsItem().apply { this.id = id; name = "dup2" }) }
        }
        val failed = events.single { !it.succeeded }
        assertTrue(failed.error is UniqueViolationException)
        assertTrue(failed.sqlState != null, "a mapped violation should carry its code on the event")
        assertEquals(QueryKind.Insert, failed.kind)
        db.transaction { ObsItems.deleteWhere(Query(ObsItems.id eq id)) }
    }
}

object ObsCat : Catalog

class ObsItem : Entity() {
    var id by ObsItems.id
    var name by ObsItems.name
}

object ObsItems : Table<ObsCat, ObsItem>("obs_items", ::ObsItem) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()

    init { id; name }
}

private val obsItemsDdl = """CREATE TABLE IF NOT EXISTS "obs_items" ("id" TEXT NOT NULL, "name" TEXT NOT NULL, PRIMARY KEY ("id"))"""
