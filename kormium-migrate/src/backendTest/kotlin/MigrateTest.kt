import io.github.kormium.Catalog
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.migrate.Migration
import io.github.kormium.migrate.MigrationChecksumException
import io.github.kormium.migrate.migrate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs on both JVM and native against a real SQLite :memory: database. */
class MigrateTest {

    private object Cat : Catalog

    private fun db(): Database<Cat> = createSqliteDatabase()

    private fun Database<Cat>.rowCount(table: String): Int =
        autocommit { execute("SELECT COUNT(*) FROM $table") { it.getInt(0)!! } }.first()

    @Test
    fun appliesPendingOnceAndIsIdempotent() {
        db().use { db ->
            val migrations = listOf(
                Migration<Cat>("001-create", """CREATE TABLE "items" ("id" integer PRIMARY KEY)"""),
                Migration<Cat>("002-seed", """INSERT INTO "items" ("id") VALUES (1)"""),
            )
            db.migrate(migrations)
            db.migrate(migrations) // already applied → no-op

            // The seed ran exactly once.
            assertEquals(1, db.rowCount("items"))
            // Both ids recorded, in order, with populated metadata.
            val journal = db.autocommit {
                execute("SELECT id, checksum, applied_at, idx FROM kormium_migrations ORDER BY idx") { rs ->
                    listOf(rs.getString(0), rs.getString(1), rs.getString(2), rs.getInt(3))
                }
            }
            assertEquals(listOf("001-create", "002-seed"), journal.map { it[0] })
            assertEquals(listOf(0, 1), journal.map { it[3] })
            journal.forEach {
                assertTrue((it[1] as String).isNotBlank()) // checksum
                assertTrue((it[2] as String).isNotBlank()) // applied_at
            }
        }
    }

    @Test
    fun multiStatementMigrationSplitsAndApplies() {
        db().use { db ->
            db.migrate(
                listOf(
                    Migration<Cat>(
                        "001",
                        """
                        CREATE TABLE "users" ("id" integer PRIMARY KEY, "name" text NOT NULL);
                        CREATE INDEX users_name_idx ON "users" ("name");
                        """,
                    ),
                ),
            )
            // Both statements ran: the table exists and the index can be queried via sqlite_master.
            assertEquals(0, db.rowCount("users"))
            val indexes = db.autocommit {
                execute("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'users_name_idx'") { it.getString(0) }
            }
            assertEquals(listOf("users_name_idx"), indexes)
        }
    }

    @Test
    fun editedAppliedMigrationFailsChecksum() {
        db().use { db ->
            db.migrate(listOf(Migration<Cat>("001", """CREATE TABLE "t" ("id" integer)""")))
            val ex = assertFailsWith<MigrationChecksumException> {
                db.migrate(listOf(Migration<Cat>("001", """CREATE TABLE "t" ("id" bigint)""")))
            }
            assertEquals("001", ex.id)
        }
    }

    @Test
    fun midBatchFailureRollsBackWholeBatch() {
        db().use { db ->
            val batch = listOf(
                Migration<Cat>("001", """CREATE TABLE "rollme" ("id" integer)"""),
                Migration<Cat>("002", "this is not valid sql"),
            )
            assertFails { db.migrate(batch) }
            // The whole transaction rolled back: neither the table nor the journal survives.
            assertFails { db.autocommit { execute("""SELECT * FROM "rollme"""") { it.getInt(0) } } }
        }
    }
}
