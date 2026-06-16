import io.github.kormium.NotificationTransport
import io.github.kormium.connectNotifications
import io.github.kormium.database.Database
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.transaction
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// An in-memory transport: `published` records what was sent out; `emitRemote` injects a signal
// as if another instance committed it.
private class FakeTransport : NotificationTransport {
    val published = Channel<Set<String>>(Channel.UNLIMITED)
    private val incoming = Channel<Set<String>>(Channel.UNLIMITED)
    override suspend fun publish(tables: Set<String>) { published.send(tables) }
    override fun subscribe(): Flow<Set<String>> = incoming.receiveAsFlow()
    suspend fun emitRemote(tables: Set<String>) { incoming.send(tables) }
}

class NotificationTransportTest {

    @Test
    fun localCommitIsPublished() = runTest {
        val mock = DatabaseMock()
        val db: Database<TestCatalog> = mock
        val sdb: SuspendDatabase<TestCatalog> = mock
        val transport = FakeTransport()
        val reg = sdb.connectNotifications(transport)

        db.transaction { TestTable.insert(TestEntity()) }

        assertEquals(setOf("products"), transport.published.receive())
        reg.remove()
    }

    @Test
    fun remoteSignalFiresLocalListeners() = runTest {
        val mock = DatabaseMock()
        val sdb: SuspendDatabase<TestCatalog> = mock
        val transport = FakeTransport()
        val fired = Channel<Set<String>>(Channel.UNLIMITED)
        sdb.writeListeners.add { fired.trySend(it) }
        val reg = sdb.connectNotifications(transport)

        transport.emitRemote(setOf("products"))

        assertEquals(setOf("products"), fired.receive())
        reg.remove()
    }

    @Test
    fun remoteSignalIsNotEchoed() = runTest {
        val mock = DatabaseMock()
        val db: Database<TestCatalog> = mock
        val sdb: SuspendDatabase<TestCatalog> = mock
        val transport = FakeTransport()
        val fired = Channel<Set<String>>(Channel.UNLIMITED)
        sdb.writeListeners.add { fired.trySend(it) }
        val reg = sdb.connectNotifications(transport)

        // A remote signal must reach local listeners (so observe re-fires cluster-wide)...
        transport.emitRemote(setOf("orders"))
        assertEquals(setOf("orders"), fired.receive())

        // ...but it must NOT be re-published. A real local commit IS published; the first (and
        // only) published item being "products" proves the remote "orders" was not echoed back.
        db.transaction { TestTable.insert(TestEntity()) }
        assertEquals(setOf("products"), transport.published.receive())
        assertNull(transport.published.tryReceive().getOrNull())
        reg.remove()
    }

    @Test
    fun removeStopsPublishing() = runTest {
        val mock = DatabaseMock()
        val db: Database<TestCatalog> = mock
        val sdb: SuspendDatabase<TestCatalog> = mock
        val transport = FakeTransport()
        val reg = sdb.connectNotifications(transport)
        reg.remove()

        db.transaction { TestTable.insert(TestEntity()) }

        assertNull(transport.published.tryReceive().getOrNull())
    }
}
