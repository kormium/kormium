import io.github.kormium.DatabaseClosedException
import io.github.kormium.DatabaseLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit coverage for the shared open/closed state machine backends compose. */
class DatabaseLifecycleTest {

    @Test
    fun isClosedFlipsOnClose() {
        val lifecycle = DatabaseLifecycle {}
        assertFalse(lifecycle.isClosed)
        lifecycle.close()
        assertTrue(lifecycle.isClosed)
    }

    @Test
    fun teardownRunsExactlyOnceAcrossManyCloses() {
        var teardowns = 0
        val lifecycle = DatabaseLifecycle { teardowns++ }
        lifecycle.close()
        lifecycle.close()
        lifecycle.close()
        assertEquals(1, teardowns, "close() must be idempotent — teardown runs once")
    }

    @Test
    fun checkOpenPassesWhileOpenAndThrowsOnceClosed() {
        val lifecycle = DatabaseLifecycle {}
        lifecycle.checkOpen() // no throw while open
        lifecycle.close()
        assertFailsWith<DatabaseClosedException> { lifecycle.checkOpen() }
    }
}
