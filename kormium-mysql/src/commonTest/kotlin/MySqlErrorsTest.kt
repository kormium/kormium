import io.github.kormium.ConcurrencyConflictException
import io.github.kormium.LockNotAvailableException
import io.github.kormium.QueryException
import io.github.kormium.UniqueViolationException
import io.github.kormium.mysqlVendorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MySQL hides the lock failures under SQLSTATE `HY000`, so only the vendor code distinguishes
 * them — see [mysqlVendorException].
 */
class MySqlErrorsTest {

    @Test
    fun mapsTheLockCodesToLockNotAvailable() {
        // 3572 = ER_LOCK_NOWAIT (a refused NOWAIT), 1205 = ER_LOCK_WAIT_TIMEOUT.
        for (code in listOf(3572, 1205)) {
            val e = mysqlVendorException("x", code, "HY000")
            assertTrue(e is LockNotAvailableException, "vendor code $code mapped to ${e::class.simpleName}")
            assertTrue(e !is ConcurrencyConflictException, "vendor code $code must not read as retryable")
        }
    }

    @Test
    fun aDeadlockStaysARetryableConflict() {
        // 1213 is not in the table: MySQL reports it under SQLSTATE 40001, and the fallback maps
        // that to the retryable conflict. Unlike the two above, it aborts the whole transaction.
        assertTrue(mysqlVendorException("x", 1213, "40001") is ConcurrencyConflictException)
    }

    @Test
    fun keepsTheExistingIntegrityMapping() {
        assertTrue(mysqlVendorException("x", 1062, "23000") is UniqueViolationException)
        assertEquals(QueryException::class, mysqlVendorException("x", 9999, null)::class)
    }
}
