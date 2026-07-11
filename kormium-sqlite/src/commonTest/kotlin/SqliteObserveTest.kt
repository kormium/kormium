@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.decimal.Decimal
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.observe.observe
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * End-to-end test of kormium-observe over a real SQLite backend (JVM + Native): a write committed
 * through [suspendTransaction] must drive the observed [io.github.kormium.observe.observe] flow
 * to re-query and emit the new state. This exercises the whole chain — Scope dirty-table
 * tracking → WriteListeners.fire on commit → the flow's re-fetch.
 *
 * The query is filtered to a unique id so it is robust against the shared-cache `:memory:`
 * database other tests also use.
 */
class SqliteObserveTest {

    @Test
    fun observeReEmitsAfterRealCommit() = runBlocking {
        val db: SuspendDatabase<SqCatalog> = createSqliteDatabase(":memory:")
        db.suspendTransaction { Products.execSql(productsDdl) }
        val myId = Uuid.random()

        val emissions = Channel<List<Product>>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) {
            Products.observe(db) { where { Products.id eq myId } }.collect { emissions.send(it) }
        }

        // Wait for the initial emission, which also guarantees the listener is registered
        // before we write (registration happens before the flow's first emit).
        val initial = withTimeout(5_000) { emissions.receive() }
        assertEquals(0, initial.size, "no row with this id yet")

        db.suspendTransaction {
            Products.insert(Product().apply {
                id = myId
                price = Decimal.of(7)
                qty = 1
                displayName = "observed"
                note = null
                rank = null
            })
        }

        // After the commit fires the write listener, the flow must re-query and surface our row.
        val seen = withTimeout(5_000) {
            var latest: List<Product> = initial
            while (latest.none { it.id == myId }) latest = emissions.receive()
            latest
        }
        assertEquals("observed", seen.single { it.id == myId }.displayName)

        job.cancelAndJoin()
        db.close()
    }
}
