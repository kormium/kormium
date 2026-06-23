package io.github.kormium.sqlite.wasm

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.count
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// wa-sqlite's async build fetches its .wasm, which Node's fetch rejects for file://. Under Node we
// read the wasm off disk and hand it to the factory as `wasmBinary`, bypassing fetch. (In the
// browser this isn't needed — the factory fetches the asset over http.)
private fun nodeWasmConfig(): JsAny =
    js("(function(){ var fs = require('node:fs'); var p = require.resolve('wa-sqlite/dist/wa-sqlite-async.wasm'); return { wasmBinary: fs.readFileSync(p) }; })()")

/**
 * End-to-end test of the wa-sqlite engine under Node: the same Table DSL the JDBC/native backends
 * use, driving an in-memory SQLite (`:memory:`) through suspendTransaction/suspendAutocommit.
 */
class SqliteWasmIntegrationTest {

    @Test
    fun crudRoundTrip() = runTest {
        val db: SuspendDatabase<WidgetCatalog> = createSqliteWasmDatabase(moduleConfig = nodeWasmConfig())
        try {
            val id = Uuid.random()
            db.suspendTransaction {
                Widgets.execSql(widgetsDdl)
                Widgets.insert(Widget().apply { this.id = id; this.name = "wasm-sqlite"; this.qty = 7 })
            }

            val found = db.suspendAutocommit { Widgets.findById(id) }
            assertEquals(id, found?.id)
            assertEquals("wasm-sqlite", found?.name)
            assertEquals(7, found?.qty)

            val byName = db.suspendAutocommit { Widgets.find(Query(Widgets.name eq "wasm-sqlite")) }
            assertEquals(1, byName.size)

            assertTrue(db.suspendAutocommit { Widgets.count() } >= 1)
        } finally {
            db.close()
        }
    }

    @Test
    fun transactionRollsBackOnThrow() = runTest {
        val db: SuspendDatabase<WidgetCatalog> = createSqliteWasmDatabase(moduleConfig = nodeWasmConfig())
        try {
            db.suspendTransaction { Widgets.execSql(widgetsDdl) }
            val id = Uuid.random()
            try {
                db.suspendTransaction {
                    Widgets.insert(Widget().apply { this.id = id; this.name = "doomed"; this.qty = 1 })
                    error("boom")
                }
            } catch (_: IllegalStateException) {
                // expected
            }
            assertNull(db.suspendAutocommit { Widgets.findById(id) })
        } finally {
            db.close()
        }
    }
}

object WidgetCatalog : Catalog

class Widget : Entity() {
    var id by Widgets.id
    var name by Widgets.name
    var qty by Widgets.qty
}

object Widgets : Table<WidgetCatalog, Widget>("widgets", ::Widget) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val qty by Column.Int()

    init { id; name; qty }
}

private val widgetsDdl =
    """CREATE TABLE IF NOT EXISTS "widgets" ("id" text NOT NULL, "name" text NOT NULL, "qty" integer NOT NULL, PRIMARY KEY ("id"))"""
