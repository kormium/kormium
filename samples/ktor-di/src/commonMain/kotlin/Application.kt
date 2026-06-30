@file:OptIn(ExperimentalUuidApi::class)

package io.github.kormium.samples.ktordi

import io.github.kormium.KormiumException
import io.github.kormium.Query
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.database.createDatabase
import io.github.kormium.eq
import io.github.kormium.ktor.di.autocommit
import io.github.kormium.ktor.di.transaction
import io.github.kormium.ktor.httpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Wires Kormium into Ktor through the built-in DI:
 *  - `dependencies { provide<SuspendDatabase<AppCatalog>> { ... } }` registers the database. Ktor DI
 *    keys by the full parameterized type (so catalogs don't collide) and auto-closes it on shutdown
 *    (a [SuspendDatabase] is `AutoCloseable`) — no lifecycle plugin needed.
 *  - routes use the reified `call.transaction<AppCatalog> { }` / `call.autocommit<AppCatalog> { }`
 *    helpers from `kormium-ktor-di`, which resolve the database from DI for you.
 */
fun Application.module() {
    dependencies {
        provide<SuspendDatabase<AppCatalog>> {
            createDatabase(
                host = "localhost",
                port = 5432,
                database = "postgres",
                user = "postgres",
                password = "password",
            )
        }
    }
    configure()
}

/** Plugins + routes, without creating the database — so tests can register their own. */
fun Application.configure() {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        // kormium-ktor only supplies the status-code mapping; you own the body format.
        exception<KormiumException> { call, e -> call.respond(e.httpStatusCode(), e.message ?: "database error") }
    }

    routing {
        // Catalog given as a type argument; `_` lets the return type infer.
        get("/") {
            val products = call.autocommit<AppCatalog, _> { ProductTable.all() }
            call.respond(products.map { it.toDto() })
        }
        put("/create") {
            val created = call.receive<ProductDTO>().toDomain().apply { id = Uuid.random() }
            // returning = true fetches the stored row back (RETURNING).
            val stored = call.transaction<AppCatalog, _> { ProductTable.insert(created, returning = true) }
            call.respond(stored!!.toDto())
        }
        post("/update") {
            val id = Uuid.parse(call.parameters["id"].orEmpty())
            val patch = call.receive<ProductDTO>().toDomain()
            val updated = call.transaction<AppCatalog, _> {
                ProductTable.update(Query(ProductTable.id eq id), patch)
                ProductTable.findOne { where { ProductTable.id eq id } }
            }
            call.respond(updated!!.toDto())
        }
        delete("/delete") {
            val id = Uuid.parse(call.parameters["id"].orEmpty())
            call.transaction<AppCatalog, _> { ProductTable.deleteWhere(Query(ProductTable.id eq id)) }
            call.respond(true)
        }
    }
}
