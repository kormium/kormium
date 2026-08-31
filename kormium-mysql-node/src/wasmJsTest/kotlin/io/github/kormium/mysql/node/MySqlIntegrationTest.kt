@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.mysql.node

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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private fun env(name: String, fallback: String): String = js("process.env[name] || fallback")

/**
 * End-to-end test of the Node MySQL engine (mysql2) against a real MySQL/MariaDB. Connection comes
 * from KORMIUM_MY_* env vars (defaults target the dev docker container); skips if unreachable.
 */
class MySqlIntegrationTest {

    private suspend fun open(): SuspendDatabase<WidgetCatalog>? {
        val db: SuspendDatabase<WidgetCatalog> = createNodeMysqlDatabase(
            host = env("KORMIUM_MY_HOST", "localhost"),
            port = env("KORMIUM_MY_PORT", "3307").toInt(),
            database = env("KORMIUM_MY_DB", "kormium_test"),
            user = env("KORMIUM_MY_USER", "root"),
            password = env("KORMIUM_MY_PASSWORD", "kormium"),
        )
        // The pool connects lazily, so probe a connection here to skip cleanly if no server is up.
        return try {
            db.suspendAutocommit { }
            db
        } catch (e: Throwable) {
            println("Skipping MySqlIntegrationTest: no MySQL reachable (${e.message})")
            db.close()
            null
        }
    }

    @Test
    fun crudRoundTrip() = runTest {
        val db = open() ?: return@runTest
        try {
            db.suspendTransaction { Widgets.execSql(widgetsDdl) }
            val id = Uuid.random()
            db.suspendTransaction {
                Widgets.insert(Widget().apply { this.id = id; this.name = "node-mysql"; this.qty = 7 })
            }

            val found = db.suspendAutocommit { Widgets.findOne { where { Widgets.id eq id } } }
            assertEquals(id, found?.id)
            assertEquals("node-mysql", found?.name)
            assertEquals(7, found?.qty)

            val byName = db.suspendAutocommit { Widgets.find(Query(Widgets.name eq "node-mysql")) }
            assertEquals(1, byName.size)
            assertTrue(db.suspendAutocommit { Widgets.count() } >= 1)

            db.suspendTransaction { Widgets.deleteWhere { where { Widgets.id eq id } } }
            assertNull(db.suspendAutocommit { Widgets.findOne { where { Widgets.id eq id } } })
        } finally {
            db.close()
        }
    }

    @Test
    fun bytesRoundTrip() = runTest {
        val db = open() ?: return@runTest
        try {
            db.suspendTransaction { Blobs.execSql(blobsDdl) }
            val id = Uuid.random()
            val payload = byteArrayOf(0, 1, 2, 42, 127, -1, -128, 99)
            db.suspendTransaction { Blobs.insert(BlobRow().apply { this.id = id; this.data = payload }) }
            val found = db.suspendAutocommit { Blobs.findOne { where { Blobs.id eq id } } }
            assertContentEquals(payload, found?.data)
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
            assertNull(db.suspendAutocommit { Widgets.findOne { where { Widgets.id eq id } } })
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

object Widgets : Table<WidgetCatalog, Widget>("my_node_widgets", ::Widget) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val qty by Column.Int()

    init { id; name; qty }
}

private val widgetsDdl =
    "CREATE TABLE IF NOT EXISTS `my_node_widgets` " +
        "(`id` CHAR(36) NOT NULL, `name` VARCHAR(255) NOT NULL, `qty` INT NOT NULL, PRIMARY KEY (`id`))"

class BlobRow : Entity() {
    var id by Blobs.id
    var data by Blobs.data
}

object Blobs : Table<WidgetCatalog, BlobRow>("my_node_blobs", ::BlobRow) {
    val id by Column.UUID().primaryKey()
    val data by Column.Bytes()

    init { id; data }
}

private val blobsDdl =
    "CREATE TABLE IF NOT EXISTS `my_node_blobs` (`id` CHAR(36) NOT NULL, `data` BLOB NOT NULL, PRIMARY KEY (`id`))"
