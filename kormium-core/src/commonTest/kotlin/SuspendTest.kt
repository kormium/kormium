import io.github.kormium.eq
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SuspendTest {

    @Test
    fun suspendTransactionRunsTheBlock() = runTest {
        TableTest.suspendDb.suspendTransaction {
            TestTable.findOne { where { TestTable.id eq Uuid.random() } }
        }
        assertTrue(TableTest.databaseMockObj.internalSql.contains("SELECT"))
    }

    @Test
    fun suspendAutocommitReturnsValue() = runTest {
        val result: List<TestEntity> = TableTest.suspendDb.suspendAutocommit { TestTable.all() }
        assertEquals(emptyList(), result)
        assertTrue(TableTest.databaseMockObj.internalSql.contains("SELECT"))
    }
}
