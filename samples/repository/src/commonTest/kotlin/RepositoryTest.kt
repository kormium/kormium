@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.samples.repository

import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs on JVM and native (SQLite is self-contained). */
class RepositoryTest {

    // Every createSqliteDatabase() call opens its own private in-memory database, so a test only
    // has to create the schema — there is nothing left over from the previous one to clear.
    private suspend fun freshDb(): SuspendDatabase<Shop> {
        val db: SuspendDatabase<Shop> = createSqliteDatabase()
        db.suspendTransaction { Users.execSql(usersDdl); Orders.execSql(ordersDdl) }
        return db
    }

    @Test
    fun crudCustomQueryAndCrossRepoTransaction() = runBlocking {
        val db = freshDb()
        val users = UserRepository(db)
        val orders = OrderRepository(db)

        users.insert(user(1, "Alice", 30))
        users.insert(user(2, "Bob", 16))

        assertEquals("Alice", users.findById(1)?.name)
        assertEquals(listOf("Alice"), users.adults().map { it.name })   // custom query

        ShopService(db, users, orders).register(user(4, "Dave", 22), order(100, userId = 4, total = 50))
        assertEquals(listOf(100), orders.all().map { it.id })           // cross-repo tx committed
        assertEquals(setOf("Alice", "Dave"), users.adults().map { it.name }.toSet())
        db.close()
    }

    @Test
    fun observeAdultsReEmitsOnInsert() = runBlocking {
        val db = freshDb()
        val users = UserRepository(db)
        users.insert(user(1, "Alice", 30))

        val emissions = Channel<List<String>>(Channel.UNLIMITED)
        val watcher = launch(Dispatchers.Default) {
            users.observeAdults().collect { emissions.send(it.map { u -> u.name }) }
        }

        // Initial value (also guarantees the observer is registered before we write).
        assertEquals(listOf("Alice"), withTimeout(5_000) { emissions.receive() })

        users.insert(user(2, "Carol", 41))   // an adult -> the Flow must re-emit

        val withCarol = withTimeout(5_000) {
            var latest = listOf<String>()
            while (!latest.contains("Carol")) latest = emissions.receive()
            latest
        }
        assertEquals(setOf("Alice", "Carol"), withCarol.toSet())

        watcher.cancelAndJoin()
        db.close()
    }
}
