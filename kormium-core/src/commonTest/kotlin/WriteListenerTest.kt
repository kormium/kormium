import io.github.kormium.autocommit
import io.github.kormium.database.Database
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.suspendTransaction
import io.github.kormium.transaction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WriteListenerTest {

    private fun freshDb(): Pair<Database<TestCatalog>, SuspendDatabase<TestCatalog>> {
        val mock = DatabaseMock()
        return mock to mock
    }

    @Test
    fun writeFiresListenerWithTableName() {
        val (db, _) = freshDb()
        var fired: Set<String>? = null
        db.writeListeners.add { fired = it }

        db.transaction { TestTable.insert(TestEntity()) }

        assertEquals(setOf("products"), fired)
    }

    @Test
    fun readOnlyTransactionDoesNotFire() {
        val (db, _) = freshDb()
        var fired = false
        db.writeListeners.add { fired = true }

        db.transaction { TestTable.find { } }

        assertTrue(!fired, "a read-only transaction must not notify write listeners")
    }

    @Test
    fun rolledBackTransactionDoesNotFire() {
        val (db, _) = freshDb()
        var fired = false
        db.writeListeners.add { fired = true }

        assertFailsWith<IllegalStateException> {
            db.transaction {
                TestTable.insert(TestEntity())
                throw IllegalStateException("boom")
            }
        }

        assertTrue(!fired, "a transaction that throws (rolls back) must not notify")
    }

    @Test
    fun multipleTablesAreCollected() {
        val (db, _) = freshDb()
        var fired: Set<String>? = null
        db.writeListeners.add { fired = it }

        db.transaction {
            TestTable.insert(TestEntity())
            TestOrders.insert(TestOrderEntity())
        }

        assertEquals(setOf("products", "orders"), fired)
    }

    @Test
    fun transactionContractAllowsValInitialization() {
        // Compiles only because transaction declares callsInPlace(block, EXACTLY_ONCE):
        // the contract lets a val be assigned inside the block and read after.
        val (db, _) = freshDb()
        val collected: String
        db.transaction { collected = TestTable.tableName }
        assertEquals("products", collected)
    }

    @Test
    fun removedListenerStopsReceiving() {
        val (db, _) = freshDb()
        var count = 0
        val registration = db.writeListeners.add { count++ }

        db.transaction { TestTable.insert(TestEntity()) }
        registration.remove()
        db.transaction { TestTable.insert(TestEntity()) }

        assertEquals(1, count)
    }

    @Test
    fun rawExecuteUpdateFiresOnlyWhenInvalidatesDeclared() {
        val (db, _) = freshDb()
        var fired: Set<String>? = null
        db.writeListeners.add { fired = it }

        // Raw SQL with no declared tables: Kormium can't know what it touched → no notification.
        db.transaction { executeUpdate("UPDATE products SET position = 0") }
        assertEquals(null, fired, "undeclared raw write must not notify")

        // Declaring the affected table opts the raw write into notification.
        db.transaction { executeUpdate("UPDATE products SET position = 0", invalidates = listOf(TestTable)) }
        assertEquals(setOf("products"), fired)
    }

    @Test
    fun autocommitWriteFires() {
        val (db, _) = freshDb()
        var fired: Set<String>? = null
        db.writeListeners.add { fired = it }

        db.autocommit { TestTable.insert(TestEntity()) }

        assertEquals(setOf("products"), fired)
    }

    @Test
    fun suspendTransactionFires() = runTest {
        val (_, suspendDb) = freshDb()
        var fired: Set<String>? = null
        suspendDb.writeListeners.add { fired = it }

        suspendDb.suspendTransaction { TestTable.insert(TestEntity()) }

        assertEquals(setOf("products"), fired)
    }

    @Test
    fun disabledRegistryIgnoresListeners() {
        // A backend that doesn't opt in exposes WriteListeners.Disabled: add is a no-op and
        // fire never reaches anything (so it stays inert and cheap).
        var fired = false
        io.github.kormium.WriteListeners.Disabled.add { fired = true }
        io.github.kormium.WriteListeners.Disabled.fire(setOf("products"))
        assertTrue(!fired, "Disabled registry must never deliver to listeners")
    }
}
