@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.TransactionIsolation
import io.github.kormium.autocommit
import io.github.kormium.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers the portable transaction-options API (`transaction(isolation, readOnly)`) against a real
 * PostgreSQL (Testcontainers), the backend where both knobs are fully honored — unlike SQLite,
 * which only honors read-only ([SqliteTransactionOptionsTest] in kormium-sqlite).
 */
class PgTransactionOptionsTest {

    @Test
    fun isolationLevelIsAppliedToTheTransaction() {
        assumeDockerAvailable()
        // Postgres reports the level the current transaction actually runs at.
        fun levelWith(isolation: TransactionIsolation) =
            ItDatabase.transaction(isolation = isolation) {
                execute("SHOW transaction_isolation", params = emptyMap(), invalidates = emptyList()) { it.getString(0) }.first()
            }
        assertEquals("serializable", levelWith(TransactionIsolation.Serializable))
        assertEquals("repeatable read", levelWith(TransactionIsolation.RepeatableRead))
        assertEquals("read committed", levelWith(TransactionIsolation.ReadCommitted))
    }

    @Test
    fun readOnlyTransactionReportsReadOnlyAndRejectsWrites() {
        assumeDockerAvailable()
        ItDatabase.autocommit {
            executeUpdate("""CREATE TABLE IF NOT EXISTS pg_ro_widgets ("id" int PRIMARY KEY)""", params = emptyMap(), invalidates = emptyList())
        }

        val readOnlyFlag = ItDatabase.transaction(readOnly = true) {
            execute("SHOW transaction_read_only", params = emptyMap(), invalidates = emptyList()) { it.getString(0) }.first()
        }
        assertEquals("on", readOnlyFlag)

        // A write inside a read-only transaction is rejected by Postgres (SQLSTATE 25006).
        assertFailsWith<Throwable> {
            ItDatabase.transaction(readOnly = true) {
                executeUpdate("INSERT INTO pg_ro_widgets (\"id\") VALUES (1)", params = emptyMap(), invalidates = emptyList())
            }
        }
        assertEquals(0L, ItDatabase.autocommit {
            execute("SELECT count(*) FROM pg_ro_widgets", params = emptyMap(), invalidates = emptyList()) { it.getLong(0) }.first()
        })
    }

    @Test
    fun connectionStateIsRestoredAfterAnOptionedTransaction() {
        assumeDockerAvailable()
        ItDatabase.autocommit {
            executeUpdate("""CREATE TABLE IF NOT EXISTS pg_rw_widgets ("id" int PRIMARY KEY)""", params = emptyMap(), invalidates = emptyList())
            executeUpdate("DELETE FROM pg_rw_widgets", params = emptyMap(), invalidates = emptyList())
        }
        // A read-only + serializable transaction must not leave the (pooled) connection read-only
        // or stuck at serializable for the next writer.
        ItDatabase.transaction(isolation = TransactionIsolation.Serializable, readOnly = true) {
            execute("SELECT 1", params = emptyMap(), invalidates = emptyList()) { it.getInt(0) }
        }
        ItDatabase.transaction {
            executeUpdate("INSERT INTO pg_rw_widgets (\"id\") VALUES (1)", params = emptyMap(), invalidates = emptyList())
        }
        // The next transaction is back to the default read-committed isolation.
        val isolation = ItDatabase.transaction {
            execute("SHOW transaction_isolation", params = emptyMap(), invalidates = emptyList()) { it.getString(0) }.first()
        }
        assertEquals("read committed", isolation)
        assertEquals(1L, ItDatabase.autocommit {
            execute("SELECT count(*) FROM pg_rw_widgets", params = emptyMap(), invalidates = emptyList()) { it.getLong(0) }.first()
        })
    }

    private fun assumeDockerAvailable() =
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
}
