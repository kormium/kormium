@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.samples.ktorkoin

import io.github.kormium.database.SuspendDatabase
import io.github.kormium.database.createDatabase
import io.github.kormium.suspendAutocommit
import kotlinx.coroutines.runBlocking
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Top-level so `install` resolves against Application only (inside application { } both
// Application.install and ApplicationTestBuilder.install are in scope → ambiguous).
private fun Application.registerKoin(db: SuspendDatabase<AppCatalog>) {
    install(Koin) { modules(module { single<SuspendDatabase<AppCatalog>> { db } }) }
}

/** Exercises the ktor-koin sample end-to-end against a real Postgres (Testcontainers, JVM only). */
class KtorKoinTest {

    @Test
    fun createThenList() {
        if (!DockerClientFactory.instance().isDockerAvailable) return // skip without Docker

        val pg = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        try {
            val db: SuspendDatabase<AppCatalog> = createDatabase(
                host = pg.host,
                port = pg.firstMappedPort,
                database = pg.databaseName,
                user = pg.username,
                password = pg.password,
            )
            runBlocking { db.suspendAutocommit { ProductTable.execSql("DROP TABLE IF EXISTS \"products\""); ProductTable.execSql(productTableDdl) } }

            testApplication {
                application {
                    registerKoin(db)
                    configure()
                }

                val created = client.put("/create") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"price":7,"payload":{"k":"v"}}""")
                }
                assertEquals(HttpStatusCode.OK, created.status)

                val list = client.get("/")
                assertEquals(HttpStatusCode.OK, list.status)
                assertTrue(list.bodyAsText().contains("\"price\":7"), "list body: ${list.bodyAsText()}")
            }

            db.close()
        } finally {
            pg.stop()
        }
    }
}
