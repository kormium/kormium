@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.eq
import io.github.kormium.transaction
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
private fun byteaEnv(name: String): String? = getenv(name)?.toKString()

/**
 * `bytea` round-trip through the native (libpq) driver.
 *
 * Regression: neither direction worked and neither had a test. Writing sent a `ByteArray`
 * through `toString()`, storing an object identity; reading did `getString()?.encodeToByteArray()`,
 * which re-encodes PostgreSQL's text encoding of the value rather than decoding it. Both are
 * silent — no error, just wrong bytes — so only a round-trip assertion catches them.
 *
 * Skipped when KORMIUM_DB_HOST is unset, like the other native integration tests.
 */
class NativeByteaIntegrationTest {

    private fun db(): Database<ByteaCat> = createDatabase(
        host = byteaEnv("KORMIUM_DB_HOST") ?: "localhost",
        port = byteaEnv("KORMIUM_DB_PORT")?.toInt() ?: 5432,
        database = byteaEnv("KORMIUM_DB_NAME") ?: "postgres",
        user = byteaEnv("KORMIUM_DB_USER") ?: "postgres",
        password = byteaEnv("KORMIUM_DB_PASSWORD") ?: "password",
        poolSize = 1,
    )

    private fun skip(): Boolean {
        if (byteaEnv("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping native bytea integration test")
            return true
        }
        return false
    }

    @Test
    fun byteaRoundTripsThroughTheDriver() {
        if (skip()) return
        db().use { database ->
            database.transaction { ByteaRows.execSql(BYTEA_DDL) }

            val payload = "Hello".encodeToByteArray()
            val id = Uuid.random()
            database.transaction {
                ByteaRows.insert(ByteaRow().apply { this.id = id; this.data = payload; this.optional = null })
            }

            val row = database.autocommit { ByteaRows.findOne { where { ByteaRows.id eq id } } }
            assertEquals(5, row?.data?.size, "5 bytes went in; a different length means the encoding was stored")
            assertContentEquals(payload, row?.data)
            assertNull(row?.optional)
        }
    }

    @Test
    fun everyByteValueSurvivesTheRoundTrip() {
        if (skip()) return
        db().use { database ->
            database.transaction { ByteaRows.execSql(BYTEA_DDL) }

            // All 256 values, including 0x00 (which would truncate a C-string round trip),
            // 0x5C (backslash) and 0x27 (quote).
            val payload = ByteArray(256) { (it - 128).toByte() }
            val id = Uuid.random()
            database.transaction {
                ByteaRows.insert(ByteaRow().apply { this.id = id; this.data = payload; this.optional = payload })
            }

            val row = database.autocommit { ByteaRows.findOne { where { ByteaRows.id eq id } } }
            assertContentEquals(payload, row?.data)
            assertContentEquals(payload, row?.optional)
        }
    }

    @Test
    fun emptyByteArrayRoundTrips() {
        if (skip()) return
        db().use { database ->
            database.transaction { ByteaRows.execSql(BYTEA_DDL) }

            val id = Uuid.random()
            database.transaction {
                ByteaRows.insert(ByteaRow().apply { this.id = id; this.data = ByteArray(0); this.optional = null })
            }

            val row = database.autocommit { ByteaRows.findOne { where { ByteaRows.id eq id } } }
            assertEquals(0, row?.data?.size)
        }
    }
}

internal val BYTEA_DDL =
    """CREATE TABLE IF NOT EXISTS "bytea_rows" ("id" uuid PRIMARY KEY, "data" bytea NOT NULL, "optional" bytea)"""

object ByteaCat : Catalog

object ByteaRows : Table<ByteaCat, ByteaRow>("bytea_rows", ::ByteaRow) {
    val id by Column.UUID().primaryKey()
    val data by Column.Bytes()
    val optional by Column.Bytes().nullable()

    init { id; data; optional }
}

class ByteaRow : Entity() {
    var id by ByteaRows.id
    var data by ByteaRows.data
    var optional by ByteaRows.optional
}
