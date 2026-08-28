import io.github.kormium.SqliteDialect
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-function tests of [SqliteDialect]'s rendering — no database needed. These pin the
 * SQLite-specific `LIMIT`/`OFFSET` shape, so a regression shows up here rather than only as a
 * syntax error in the integration suites.
 */
class SqliteDialectTest {

    @Test
    fun limitOffsetSqliteSemantics() {
        assertEquals("", SqliteDialect.renderLimitOffset(UInt.MAX_VALUE, 0u))
        assertEquals("LIMIT 10 ", SqliteDialect.renderLimitOffset(10u, 0u))
        assertEquals("LIMIT 10 OFFSET 5 ", SqliteDialect.renderLimitOffset(10u, 5u))
        // A bare OFFSET is a syntax error in SQLite ("near OFFSET"), so an offset with no real
        // limit carries SQLite's documented "no limit" sentinel.
        assertEquals("LIMIT -1 OFFSET 5 ", SqliteDialect.renderLimitOffset(UInt.MAX_VALUE, 5u))
    }
}
