package io.github.kormium.samples.repository

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.gtEq
import io.github.kormium.observe.observe
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// ---- schema + entities (owned by the app: raw DDL, not Kormium) ----

object Shop : Catalog

object Users : Table<Shop, User>("users", ::User) {
    val id by Column.Int().primaryKey()
    val name by Column.Text()
    val age by Column.Int()
}

class User : Entity() {
    var id by Users.id
    var name by Users.name
    var age by Users.age
}

object Orders : Table<Shop, Order>("orders", ::Order) {
    val id by Column.Int().primaryKey()
    val userId by Column.Int()
    val total by Column.Int()
}

class Order : Entity() {
    var id by Orders.id
    var userId by Orders.userId
    var total by Orders.total
}

internal val usersDdl =
    """CREATE TABLE IF NOT EXISTS "users" ("id" INTEGER NOT NULL, "name" TEXT NOT NULL, "age" INTEGER NOT NULL, PRIMARY KEY ("id"))"""
internal val ordersDdl =
    """CREATE TABLE IF NOT EXISTS "orders" ("id" INTEGER NOT NULL, "userId" INTEGER NOT NULL, "total" INTEGER NOT NULL, PRIMARY KEY ("id"))"""

// ---- repositories (extend the copied Repository base, add domain queries) ----

class UserRepository(db: SuspendDatabase<Shop>) : Repository<Shop, User, Int>(db, Users, Users.id) {
    suspend fun adults(): List<User> = read { Users.find { where { Users.age gtEq 18 } } }
    fun observeAdults(): Flow<List<User>> = Users.observe(db) { where { Users.age gtEq 18 } }
}

class OrderRepository(db: SuspendDatabase<Shop>) : Repository<Shop, Order, Int>(db, Orders, Orders.id)

// ---- a service that needs a cross-repository transaction ----

class ShopService(
    private val db: SuspendDatabase<Shop>,
    val users: UserRepository,
    val orders: OrderRepository,
) {
    // Repository methods each open their own transaction, so to make two writes atomic we wrap
    // the table operations in ONE outer transaction (the Unit of Work lives in the service).
    suspend fun register(user: User, firstOrder: Order) = db.suspendTransaction {
        Users.insert(user)
        Orders.insert(firstOrder)
    }
}

internal fun user(id: Int, name: String, age: Int) = User().apply { this.id = id; this.name = name; this.age = age }
internal fun order(id: Int, userId: Int, total: Int) = Order().apply { this.id = id; this.userId = userId; this.total = total }

fun main() = runBlocking {
    val db: SuspendDatabase<Shop> = createSqliteDatabase()
    db.use {
        db.suspendTransaction { Users.execSql(usersDdl); Orders.execSql(ordersDdl) }

        val users = UserRepository(db)
        val orders = OrderRepository(db)
        val shop = ShopService(db, users, orders)

        users.insert(user(1, "Alice", 30))
        users.insert(user(2, "Bob", 16))
        println("findById(1) = ${users.findById(1)?.name}")
        println("adults      = ${users.adults().map { it.name }}")

        // Reactive query: the Flow re-emits whenever the users table changes.
        val seen = mutableListOf<List<String>>()
        val watcher = launch { users.observeAdults().collect { seen += it.map { u -> u.name } } }
        delay(100)                                   // let the initial value arrive
        users.insert(user(3, "Carol", 41))           // an adult -> the Flow re-emits
        delay(100)
        watcher.cancel()
        println("observed    = $seen")               // [[Alice], [Alice, Carol]]

        // Cross-repository transaction: both writes commit together.
        shop.register(user(4, "Dave", 22), order(100, userId = 4, total = 50))
        println("orders      = ${orders.all().map { "#${it.id}:${it.total}" }}")
    }
}
