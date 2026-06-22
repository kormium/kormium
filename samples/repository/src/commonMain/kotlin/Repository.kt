package io.github.kormium.samples.repository

import io.github.kormium.Catalog
import io.github.kormium.Entity
import io.github.kormium.QueryBuilder
import io.github.kormium.SuspendScope
import io.github.kormium.Table
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.observe.observe
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Kormium intentionally does NOT ship a Repository type — like Exposed, you call table operations
 * inside `suspendTransaction { }` / `suspendAutocommit { }`. This little base is the recommended
 * pattern when you want a Room-style "home" for a table's queries; copy it into your project and
 * adapt it. It is ~25 lines and yours to change.
 *
 * Each method runs in its own scope (so it is its own transaction). To make several repository
 * operations atomic, wrap their table operations in one outer `suspendTransaction { }` instead —
 * see [ShopService.register].
 */
abstract class Repository<G : Catalog, T : Entity>(
    protected val db: SuspendDatabase<G>,
    protected val table: Table<G, T>,
) {
    suspend fun findById(id: Any): T? = read { table.findById(id) }
    suspend fun all(): List<T> = read { table.all() }
    suspend fun insert(entity: T): T? = write { table.insert(entity) }
    suspend fun deleteWhere(block: QueryBuilder.() -> Unit): Long = write { table.deleteWhere(block) }

    /** A Flow that emits the current rows now and again after every committed write to the table. */
    fun observeAll(): Flow<List<T>> = table.observe(db)

    /** Run a read on this database (no transaction). Use in subclasses for custom queries. */
    protected suspend fun <R> read(block: suspend SuspendScope<G>.() -> R): R = db.suspendAutocommit(block)

    /** Run a write in a transaction on this database. Use in subclasses for custom mutations. */
    protected suspend fun <R> write(block: suspend SuspendScope<G>.() -> R): R = db.suspendTransaction(block = block)
}

// For unit-testing services against an interface (mocking), declare a domain interface your
// service depends on and implement it via this base, e.g.:
//
//   interface UserRepo { suspend fun adults(): List<User> }
//   class DbUserRepo(db) : UserRepo, Repository<Shop, User>(db, Users) {
//       override suspend fun adults() = read { Users.find { where { Users.age gtEq 18 } } }
//   }
//
// Then a service takes `UserRepo` and tests pass a fake. This sample keeps it concrete for brevity.
