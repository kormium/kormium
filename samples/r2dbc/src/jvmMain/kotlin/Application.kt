@file:OptIn(ExperimentalUuidApi::class)

package io.github.kormium.samples.r2dbc

import io.github.kormium.KormiumException
import io.github.kormium.Query
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.ktor.di.autocommit
import io.github.kormium.ktor.di.transaction
import io.github.kormium.ktor.httpStatusCode
import io.github.kormium.r2dbc.createR2dbcDatabase
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
 * The same Kormium + Ktor wiring as the `ktor-di` sample, but backed by the ASYNC r2dbc driver
 * instead of blocking JDBC. The only difference is the registered dependency:
 *
 *     provide<SuspendDatabase<AppCatalog>> { createR2dbcDatabase(...) }
 *
 * The routes and the `call.transaction` / `call.autocommit` helpers are byte-for-byte identical
 * to the blocking sample — the suspend API is backend-transparent. r2dbc gives true non-blocking
 * I/O (and pipelining) here, where JDBC would offload to a thread.
 */
fun Application.module() {
    dependencies {
        provide<SuspendDatabase<AppCatalog>> {
            createR2dbcDatabase(
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
        exception<KormiumException> { call, e -> call.respond(e.httpStatusCode(), e.message ?: "database error") }
    }

    routing {
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
