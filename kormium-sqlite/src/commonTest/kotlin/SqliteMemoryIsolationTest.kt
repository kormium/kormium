@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Regression test for the default `:memory:` + `poolSize = 1` case sharing state process-wide
 * (https://github.com/kormium/kormium/issues/131): two independent [createSqliteDatabase] calls
 * must not see each other's data, even though both use the same default `":memory:"` path.
 */
class SqliteMemoryIsolationTest {

    @Test
    fun independentInstancesDoNotShareState() {
        val a = createSqliteDatabase()
        val b = createSqliteDatabase()
        try {
            a.autocommit {
                executeUpdate("CREATE TABLE t(x INTEGER)", params = emptyMap(), invalidates = emptyList())
            }

            // b never saw a's CREATE TABLE, so querying it here must fail with "no such table"
            // rather than see a's (nonexistent) rows.
            assertFailsWith<Exception> {
                b.autocommit { executeUpdate("SELECT * FROM t", params = emptyMap(), invalidates = emptyList()) }
            }
        } finally {
            a.close()
            b.close()
        }
    }
}
