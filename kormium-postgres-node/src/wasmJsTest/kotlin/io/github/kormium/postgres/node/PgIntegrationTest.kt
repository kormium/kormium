package io.github.kormium.postgres.node

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

private fun env(name: String, fallback: String): String = js("process.env[name] || fallback")

/**
 * End-to-end test of the Node Postgres engine (node-postgres) against a real Postgres. Connection
 * comes from KORMIUM_PG_* env vars (defaults target the docker container used in development);
 * the test skips gracefully if no server is reachable — same pattern as the r2dbc tests.
 */
class PgIntegrationTest {

    private suspend fun open(): SuspendDatabase<WidgetCatalog>? =
        try {
            createNodePostgresDatabase(
                host = env("KORMIUM_PG_HOST", "localhost"),
                port = env("KORMIUM_PG_PORT", "5433").toInt(),
                database = env("KORMIUM_PG_DB", "kormtest"),
                user = env("KORMIUM_PG_USER", "postgres"),
                password = env("KORMIUM_PG_PASSWORD", "korm"),
            )
        } catch (e: Throwable) {
            println("Skipping PgIntegrationTest: no Postgres reachable (${e.message})")
            null
        }

    @Test
    fun crudRoundTrip() = runTest {
        val db = open() ?: return@runTest
        try {
            db.suspendTransaction { Widgets.execSql(widgetsDdl) }
            val id = Uuid.random()
            db.suspendTransaction {
                Widgets.insert(Widget().apply { this.id = id; this.name = "node-pg"; this.qty = 7 })
            }

            val found = db.suspendAutocommit { Widgets.findById(id) }
            assertEquals(id, found?.id)
            assertEquals("node-pg", found?.name)
            assertEquals(7, found?.qty)

            val byName = db.suspendAutocommit { Widgets.find(Query(Widgets.name eq "node-pg")) }
            assertEquals(1, byName.size)
            assertTrue(db.suspendAutocommit { Widgets.count() } >= 1)

            db.suspendTransaction { Widgets.deleteWhere { where { Widgets.id eq id } } }
            assertNull(db.suspendAutocommit { Widgets.findById(id) })
        } finally {
            db.close()
        }
    }

    @Test
    fun transactionRollsBackOnThrow() = runTest {
        val db = open() ?: return@runTest
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

object Widgets : Table<WidgetCatalog, Widget>("pg_node_widgets", ::Widget) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val qty by Column.Int()

    init { id; name; qty }
}

private val widgetsDdl =
    """CREATE TABLE IF NOT EXISTS "pg_node_widgets" ("id" uuid NOT NULL, "name" text NOT NULL, "qty" integer NOT NULL, PRIMARY KEY ("id"))"""
