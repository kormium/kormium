import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Dialect
import io.github.kormium.Entity
import io.github.kormium.SqlParameterSource
import io.github.kormium.StandardDialect
import io.github.kormium.StandardTypeMapper
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.Table
import io.github.kormium.TypeMapper
import io.github.kormium.WriteListeners
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.observe.observe
import io.github.kormium.resultset.ResultSet
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi

// A SuspendDatabase that runs the scope block against a no-op executor and exposes a real
// WriteListeners registry, so we can drive observation by firing it directly (standing in for
// a committed write). The fetch lambdas in these tests read captured state, not the executor.
private class FakeDb : SuspendDatabase<Nothing> {
    override val writeListeners = WriteListeners()

    override suspend fun <R> useConnection(
        transactional: Boolean,
        isolation: io.github.kormium.TransactionIsolation?,
        readOnly: Boolean,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R = block(NoopExecutor)

    override fun close() {}
}

private object NoopExecutor : SuspendSqlExecutor {
    override val dialect: Dialect = StandardDialect
    override val typeMapper: TypeMapper = StandardTypeMapper
    override suspend fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> = emptyList()
    override suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> = emptyList()
    override suspend fun execute(sql: String, namedParameters: Map<String, Any?>): Long = 0L
    override suspend fun execute(sql: String, paramSource: SqlParameterSource): Long = 0L
    override suspend fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long = 0L
}

private object ObserveCatalog : Catalog

private class Widget : Entity() {
    var id by Widgets.id
}

private object Widgets : Table<ObserveCatalog, Widget>("widgets", ::Widget) {
    val id by Column.Int().primaryKey()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveTest {

    @Test
    fun emitsInitialThenReEmitsOnRelevantWriteOnly() = runTest {
        val db: SuspendDatabase<ObserveCatalog> = FakeDb()
        var value = 0
        val collected = mutableListOf<Int>()

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            db.observe(setOf("users")) { value }.collect { collected.add(it) }
        }

        advanceUntilIdle()
        assertEquals(listOf(0), collected, "should emit the initial value once")

        value = 1
        db.writeListeners.fire(setOf("users"))
        advanceUntilIdle()

        value = 2
        db.writeListeners.fire(setOf("orders")) // unrelated table → no re-emit
        advanceUntilIdle()

        value = 3
        db.writeListeners.fire(setOf("users"))
        advanceUntilIdle()

        assertEquals(listOf(0, 1, 3), collected)
        job.cancel()
    }

    @Test
    fun tableObserveReQueriesOnTableWrite() = runTest {
        val db: SuspendDatabase<ObserveCatalog> = FakeDb()
        var emissions = 0

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            Widgets.observe(db).collect { emissions++ }
        }

        advanceUntilIdle()
        assertEquals(1, emissions, "initial query result")

        db.writeListeners.fire(setOf("widgets"))
        advanceUntilIdle()
        assertEquals(2, emissions)

        db.writeListeners.fire(setOf("gadgets")) // other table → ignored
        advanceUntilIdle()
        assertEquals(2, emissions)

        job.cancel()
    }

    @Test
    fun listenerIsRemovedAfterCollectionStops() = runTest {
        val db = FakeDb()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            db.observe(setOf("users")) { 0 }.collect { }
        }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()

        assertEquals(false, db.writeListeners.isActive, "the flow must unregister its listener on cancel")
    }
}
