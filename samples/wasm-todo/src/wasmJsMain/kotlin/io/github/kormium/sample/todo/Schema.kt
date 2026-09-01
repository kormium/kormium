package io.github.kormium.sample.todo

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table

/** The catalog tag tying [Todos] to a database handle. */
object TodoCatalog : Catalog

/** One todo item. Fields are typed column delegates — no annotations, no reflection. */
class Todo : Entity() {
    var id by Todos.id
    var title by Todos.title
    var done by Todos.done
    var createdAt by Todos.createdAt
}

/** The `todos` table: the same typed DSL the JVM/Native backends use, here running on wa-sqlite. */
object Todos : Table<TodoCatalog, Todo>("todos", ::Todo) {
    val id by Column.UUID().primaryKey()
    val title by Column.Text()
    val done by Column.Boolean()
    val createdAt by Column.Instant(name = "created_at")

    init { id; title; done; createdAt }
}

/** Schema bootstrap. SQLite is dynamically typed; Kormium's non-native types are stored as TEXT. */
val todosDdl = """
    CREATE TABLE IF NOT EXISTS "todos" (
        "id" text NOT NULL,
        "title" text NOT NULL,
        "done" text NOT NULL,
        "created_at" text NOT NULL,
        PRIMARY KEY ("id")
    )
""".trimIndent()
