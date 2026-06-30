@file:OptIn(ExperimentalUuidApi::class)

package io.github.kormium.samples.ktorkoin

import io.github.kormium.KormiumException
import io.github.kormium.Query
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.database.createDatabase
import io.github.kormium.eq
import io.github.kormium.ktor.httpStatusCode
import io.github.kormium.ktor.koin.autocommit
import io.github.kormium.ktor.koin.transaction
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.ktor.plugin.Koin
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Wires Kormium into Ktor through Koin:
 *  - `install(Koin) { modules(...) }` registers the database; `onClose { it?.close() }` closes the
 *    pool when Koin shuts down with the application (Koin does not auto-close like Ktor DI does).
 *  - routes use the reified `call.transaction<AppCatalog> { }` / `call.autocommit<AppCatalog> { }`
 *    helpers from `kormium-ktor-koin`, which resolve the database from Koin.
 *
 * Note: Koin keys by `KClass`, so generics are erased. With a single catalog this is fine; for
 * multiple catalogs register and resolve with a `named(...)` qualifier (see kormium-ktor-koin docs).
 */
fun Application.module() {
    install(Koin) {
        modules(
            module {
                single<SuspendDatabase<AppCatalog>> {
                    createDatabase(
                        host = "localhost",
                        port = 5432,
                        database = "postgres",
                        user = "postgres",
                        password = "password",
                    )
                } onClose { it?.close() }
            },
        )
    }

    configure()
}

/** Plugins + routes, without installing Koin — so tests can register their own modules. */
fun Application.configure() {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
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
            val stored = call.transaction<AppCatalog, _> { ProductTable.insert(created, returning = true) }
            call.respond(stored!!.toDto())
        }
        post("/update") {
            val id = Uuid.parse(call.parameters["id"].orEmpty())
            val patch = call.receive<ProductDTO>().toDomain()
            val updated = call.transaction<AppCatalog, _> {
                ProductTable.update(patch, Query(ProductTable.id eq id))
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
