@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.TransactionIsolation
import io.github.kormium.autocommit
import io.github.kormium.count
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.eq
import io.github.kormium.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Covers the portable transaction-options API (`transaction(isolation, readOnly)`) on SQLite.
 *
 * SQLite has a single isolation level (effectively SERIALIZABLE), so a non-null [isolation] is
 * intentionally *ignored* (it must not error). Read-only IS honored, via `PRAGMA query_only`,
 * which is reset when the connection returns to the pool.
 */
class SqliteTransactionOptionsTest {

    // poolSize defaults to 1, so the read-only connection is the one reused next — exactly what
    // we need to prove PRAGMA query_only is reset on release.
    private val db: Database<RoCatalog> = createSqliteDatabase(":memory:").also { d ->
        d.transaction { executeUpdate(roWidgetsDdl, params = emptyMap(), invalidates = emptyList()) }
    }

    @Test
    fun readOnlyTransactionRejectsWrites() {
        val before = db.autocommit { RoWidgets.all().size }
        assertFailsWith<Throwable> {
            db.transaction(readOnly = true) {
                RoWidgets.insert(RoWidget().apply { id = Uuid.random(); name = "nope" })
            }
        }
        // Nothing was written.
        assertEquals(before, db.autocommit { RoWidgets.all().size })
    }

    @Test
    fun readsWorkInsideReadOnlyTransaction() {
        val id = Uuid.random()
        db.transaction { RoWidgets.insert(RoWidget().apply { this.id = id; name = "alpha" }) }

        val count = db.transaction(readOnly = true) {
            RoWidgets.count { where { RoWidgets.id eq id } }
        }
        assertEquals(1L, count)
    }

    @Test
    fun connectionIsWritableAgainAfterReadOnlyTransaction() {
        // A read-only transaction sets PRAGMA query_only; the pool (size 1) hands the same
        // connection to the next transaction, which must be able to write again.
        db.transaction(readOnly = true) { RoWidgets.all() }

        val id = Uuid.random()
        db.transaction { RoWidgets.insert(RoWidget().apply { this.id = id; name = "beta" }) }
        assertEquals(1L, db.autocommit { RoWidgets.count { where { RoWidgets.id eq id } } })
    }

    @Test
    fun isolationLevelIsAcceptedAndIgnored() {
        // SQLite has no settable isolation level; passing one must not error and must still write.
        val id = Uuid.random()
        for (level in TransactionIsolation.entries) {
            db.transaction(isolation = level) {
                RoWidgets.insert(RoWidget().apply { this.id = Uuid.random(); name = level.name })
            }
        }
        assertTrue(db.autocommit { RoWidgets.all().size } >= TransactionIsolation.entries.size)
        // Combined with read-only, isolation is still ignored and the write is still blocked.
        assertFailsWith<Throwable> {
            db.transaction(isolation = TransactionIsolation.Serializable, readOnly = true) {
                RoWidgets.insert(RoWidget().apply { this.id = id; name = "blocked" })
            }
        }
    }
}

private object RoCatalog : Catalog

private class RoWidget : Entity() {
    var id by RoWidgets.id
    var name by RoWidgets.name
}

private object RoWidgets : Table<RoCatalog, RoWidget>("ro_widgets", ::RoWidget) {
    val id by Column.UUID()
    val name by Column.Text()

    init { id; name }
}

private val roWidgetsDdl =
    """CREATE TABLE IF NOT EXISTS "ro_widgets" ("id" text PRIMARY KEY, "name" text NOT NULL)"""
