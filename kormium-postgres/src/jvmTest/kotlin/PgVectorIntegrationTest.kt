@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.Catalog
import io.github.kormium.Entity
import io.github.kormium.autocommit
import io.github.kormium.Table
import io.github.kormium.Column
import io.github.kormium.Vector
import io.github.kormium.cosineDistance
import io.github.kormium.database.createDatabase
import io.github.kormium.eq
import io.github.kormium.euclideanDistance
import io.github.kormium.inList
import io.github.kormium.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * End-to-end pgvector tests against a real Postgres with the `vector` extension (the
 * `pgvector/pgvector` image, distinct from the plain `postgres` image the other IT suites use).
 * Exercises the full round-trip — a [Vector] binds with the `::vector` cast, reads back parsed, and
 * the distance operators drive a KNN `ORDER BY`. Skipped (not failed) when Docker is unavailable.
 */
class PgVectorIntegrationTest {

    @Test
    fun vectorRoundTrips() {
        assumeDockerAvailable()
        val id = Uuid.random()
        val v = Vector.of(1f, 2f, 3f)
        VecDb.transaction {
            Docs.insert(Doc().apply { this.id = id; this.embedding = v })
            val found = Docs.findOne { where { Docs.id eq id } }
            assertEquals(v, found?.embedding)
        }
    }

    @Test
    fun knnOrderByReturnsNearestFirst() {
        assumeDockerAvailable()
        val near = Uuid.random()
        val mid = Uuid.random()
        val far = Uuid.random()
        VecDb.transaction {
            Docs.insert(Doc().apply { id = near; embedding = Vector.of(1f, 0f, 0f) })
            Docs.insert(Doc().apply { id = mid; embedding = Vector.of(0.5f, 0.5f, 0f) })
            Docs.insert(Doc().apply { id = far; embedding = Vector.of(0f, 1f, 0f) })

            val query = Vector.of(1f, 0f, 0f)
            val byL2 = Docs.find {
                where { Docs.id.inList(listOf(near, mid, far)) }
                orderBy ASC Docs.embedding.euclideanDistance(query)
            }
            assertEquals(listOf(near, mid, far), byL2.map { it.id })

            // Cosine ranks by direction; the exact-direction match is nearest.
            val byCosine = Docs.find {
                where { Docs.id.inList(listOf(near, mid, far)) }
                orderBy ASC Docs.embedding.cosineDistance(query)
                limit = 1
            }
            assertEquals(near, byCosine.single().id)
        }
    }

    @Test
    fun dimensionMismatchFailsBeforeHittingTheServer() {
        assumeDockerAvailable()
        VecDb.transaction {
            assertFailsWith<IllegalArgumentException> {
                Docs.insert(Doc().apply { id = Uuid.random(); embedding = Vector.of(1f, 2f) })
            }
        }
    }

    private fun assumeDockerAvailable() =
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
}

private object VecCatalog : Catalog

private object Docs : Table<VecCatalog, Doc>("docs", ::Doc) {
    val id by Column.UUID().primaryKey()
    val embedding by Column.Vector(dimensions = 3)

    init { id; embedding }
}

private class Doc : Entity() {
    var id by Docs.id
    var embedding by Docs.embedding
}

/** A JDBC driver against a pgvector container started once for this suite, with the schema created. */
private object VecDb : io.github.kormium.database.Database<VecCatalog> {
    private val container =
        PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .apply { start() }

    private val driver = createDatabase(
        host = container.host,
        port = container.firstMappedPort,
        database = container.databaseName,
        user = container.username,
        password = container.password,
    )

    init {
        driver.autocommit {
            executeUpdate("CREATE EXTENSION IF NOT EXISTS vector", params = emptyMap(), invalidates = emptyList())
            executeUpdate(
                """CREATE TABLE IF NOT EXISTS "docs" ("id" uuid PRIMARY KEY, "embedding" vector(3) NOT NULL)""",
                params = emptyMap(),
                invalidates = emptyList(),
            )
        }
    }

    override fun <R> usePinned(
        transactional: Boolean,
        isolation: io.github.kormium.TransactionIsolation?,
        readOnly: Boolean,
        block: (io.github.kormium.SqlExecutor) -> R,
    ): R = driver.usePinned(transactional, isolation, readOnly, block)

    override fun close() = driver.close()
}
