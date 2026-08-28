@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.samples.r2dbc

import io.github.kormium.database.SuspendDatabase
import io.github.kormium.r2dbc.createR2dbcDatabase
import io.github.kormium.suspendAutocommit
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the async r2dbc sample end-to-end against a real Postgres (Testcontainers, JVM only). */
class R2dbcSampleTest {

    @Test
    fun createThenList() {
        if (!DockerClientFactory.instance().isDockerAvailable) return // skip without Docker

        val pg = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        try {
            val db: SuspendDatabase<AppCatalog> = createR2dbcDatabase(
                host = pg.host,
                port = pg.firstMappedPort,
                database = pg.databaseName,
                user = pg.username,
                password = pg.password,
            )
            runBlocking { db.suspendAutocommit { ProductTable.execSql("DROP TABLE IF EXISTS \"products\""); ProductTable.execSql(productTableDdl) } }

            testApplication {
                application {
                    dependencies { provide<SuspendDatabase<AppCatalog>> { db } }
                    configure()
                }

                val created = client.put("/create") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"price":42,"payload":{"k":"v"}}""")
                }
                assertEquals(HttpStatusCode.OK, created.status)
                assertTrue(created.bodyAsText().contains("\"price\":42"))

                val list = client.get("/")
                assertEquals(HttpStatusCode.OK, list.status)
                assertTrue(list.bodyAsText().contains("\"price\":42"), "list body: ${list.bodyAsText()}")
            }

            db.close()
        } finally {
            pg.stop()
        }
    }
}
