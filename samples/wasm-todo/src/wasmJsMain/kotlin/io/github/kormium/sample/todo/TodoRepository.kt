@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.sample.todo

import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.sqlite.wasm.createSqliteWasmDatabase
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.datetime.Clock
import kotlin.uuid.Uuid

/**
 * Thin data layer over the Kormium DSL. The database is an embedded SQLite (wa-sqlite, SQLite in
 * WASM) persisted to IndexedDB, so todos survive a page reload. Every method runs through a suspend
 * scope — exactly the same API a JVM or Native app would call.
 */
class TodoRepository private constructor(private val db: SuspendDatabase<TodoCatalog>) {

    suspend fun all(): List<Todo> =
        db.suspendAutocommit { Todos.find { } }
            .sortedByDescending { it.createdAt }

    suspend fun add(title: String) {
        db.suspendTransaction {
            Todos.insert(
                Todo().apply {
                    id = Uuid.random()
                    this.title = title
                    done = false
                    createdAt = Clock.System.now()
                },
            )
        }
    }

    suspend fun setDone(todo: Todo, done: Boolean) {
        db.suspendTransaction {
            Todos.update(Todo().apply { this.done = done }) { where { Todos.id eq todo.id } }
        }
    }

    suspend fun remove(todo: Todo) {
        db.suspendTransaction { Todos.deleteWhere { where { Todos.id eq todo.id } } }
    }

    companion object {
        /** Opens the IndexedDB-backed SQLite database and ensures the schema exists. */
        suspend fun open(): TodoRepository {
            val db: SuspendDatabase<TodoCatalog> = createSqliteWasmDatabase("kormium-todo")
            db.suspendTransaction { Todos.execSql(todosDdl) }
            return TodoRepository(db)
        }
    }
}
