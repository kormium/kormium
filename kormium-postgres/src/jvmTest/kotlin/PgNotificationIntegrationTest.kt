@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.connectNotifications
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.postgresListenNotifyTransport
import io.github.kormium.transaction
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals

object NotifyCatalog : Catalog

class NotifyRow : Entity() {
    var id by NotifyThings.id
}

object NotifyThings : Table<NotifyCatalog, NotifyRow>("notify_things", ::NotifyRow) {
    val id by Column.Int().primaryKey()

    init { id }
}

/** Proves a commit on one driver invalidates another driver (a second "instance") via LISTEN/NOTIFY. */
class PgNotificationIntegrationTest {

    @Test
    fun commitOnOneInstanceNotifiesAnother() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
        val container = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        try {
            fun connect() = createDatabase(
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
            )

            val driverA = connect() // the "writer" instance
            val driverB = connect() // the "reader" instance, whose cache/observe must be invalidated
            // Catalog-typed handles so scope operations resolve to NotifyCatalog (the driver type
            // is Database<Nothing>, which would otherwise infer G = Nothing).
            val dbA: Database<NotifyCatalog> = driverA
            try {
                dbA.autocommit {
                    NotifyThings.execSql("""CREATE TABLE IF NOT EXISTS "notify_things" ("id" integer PRIMARY KEY)""")
                }

                runBlocking {
                    val fired = Channel<Set<String>>(Channel.UNLIMITED)
                    driverB.writeListeners.add { fired.trySend(it) }

                    val regB = driverB.connectNotifications(
                        postgresListenNotifyTransport(
                            container.host, container.firstMappedPort,
                            container.databaseName, container.username, container.password,
                        ),
                    )
                    val regA = driverA.connectNotifications(
                        postgresListenNotifyTransport(
                            container.host, container.firstMappedPort,
                            container.databaseName, container.username, container.password,
                        ),
                    )
                    try {
                        // Give dbB's dedicated LISTEN connection time to establish before A writes.
                        delay(2_000)

                        dbA.transaction { NotifyThings.insert(NotifyRow().apply { id = 1 }) }

                        val got = withTimeout(15_000) { fired.receive() }
                        assertEquals(setOf("notify_things"), got)
                    } finally {
                        regA.remove()
                        regB.remove()
                    }
                }
            } finally {
                driverA.close()
                driverB.close()
            }
        } finally {
            container.stop()
        }
    }
}
