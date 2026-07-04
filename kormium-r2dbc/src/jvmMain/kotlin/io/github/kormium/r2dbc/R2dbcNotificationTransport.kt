package io.github.kormium.r2dbc

import io.github.kormium.NotificationTransport
import io.github.kormium.decodeTablePayload
import io.github.kormium.encodeTablePayload
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle

/**
 * A Postgres `LISTEN/NOTIFY` [NotificationTransport] over r2dbc — truly async, reactive
 * notifications (no polling). Pass it to `connectNotifications` to make commit notifications cross
 * process boundaries with NO external broker.
 *
 * It opens its OWN r2dbc connections, separate from the driver pool, so it takes the same
 * connection parameters you passed to [createR2dbcDatabase]. [subscribe] holds one dedicated
 * connection in `LISTEN` and streams its reactive notification feed; [publish] opens a short-lived
 * connection per call to `NOTIFY`. The wire format is shared with the JDBC/libpq transports, so an
 * r2dbc instance interoperates with them on the same [channel].
 */
public fun r2dbcListenNotifyTransport(
    host: String,
    port: Int = 5432,
    database: String,
    user: String,
    password: String,
    channel: String = "kormium_changes",
): NotificationTransport {
    val factory = PostgresqlConnectionFactory(
        PostgresqlConnectionConfiguration.builder()
            .host(host).port(port).database(database).username(user).password(password).build(),
    )
    return R2dbcNotificationTransport(factory, channel)
}

private class R2dbcNotificationTransport(
    private val factory: PostgresqlConnectionFactory,
    private val channel: String,
) : NotificationTransport {

    override suspend fun publish(tables: Set<String>) {
        val payload = encodeTablePayload(tables).replace("'", "''")
        val conn = factory.create().awaitSingle()
        try {
            conn.createStatement("NOTIFY $channel, '$payload'").execute().asFlow().collect { }
        } finally {
            conn.close().awaitFirstOrNull()
        }
    }

    override fun subscribe(): Flow<Set<String>> = flow {
        val conn = factory.create().awaitSingle()
        try {
            conn.createStatement("LISTEN $channel").execute().asFlow().collect { }
            emitAll(conn.notifications.asFlow().mapNotNull { n -> n.parameter?.let { decodeTablePayload(it) } })
        } finally {
            conn.close().awaitFirstOrNull()
        }
    }
}
