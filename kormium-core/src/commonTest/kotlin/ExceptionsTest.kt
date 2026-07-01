import io.github.kormium.CheckViolationException
import io.github.kormium.ConcurrencyConflictException
import io.github.kormium.ForeignKeyViolationException
import io.github.kormium.NotNullViolationException
import io.github.kormium.QueryException
import io.github.kormium.UniqueViolationException
import io.github.kormium.sqlException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExceptionsTest {

    @Test
    fun mapsSqlStateToTheMostSpecificSubtype() {
        assertTrue(sqlException("x", "23505") is UniqueViolationException)
        assertTrue(sqlException("x", "23503") is ForeignKeyViolationException)
        assertTrue(sqlException("x", "23502") is NotNullViolationException)
        assertTrue(sqlException("x", "23514") is CheckViolationException)
        // 40001 (serialization failure / MySQL deadlock) and 40P01 (PG deadlock) are retryable.
        assertTrue(sqlException("x", "40001") is ConcurrencyConflictException)
        assertTrue(sqlException("x", "40P01") is ConcurrencyConflictException)
    }

    @Test
    fun unknownOrNullSqlStateStaysGeneric() {
        val unknown = sqlException("x", "99999")
        assertTrue(unknown !is ConcurrencyConflictException)
        assertEquals("99999", unknown.sqlState)
        assertEquals(QueryException::class, sqlException("x", null)::class)
    }
}
