package io.github.kormium

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.postgresql.PGConnection
import java.sql.DriverManager

/**
 * A Postgres `LISTEN/NOTIFY` [NotificationTransport] for the JVM (pgjdbc) — pass it to
 * [connectNotifications] to make commit notifications (and `kormium-observe`, and any cache)
 * cross process boundaries with NO external broker.
 *
 * It opens its OWN connections, separate from the driver's pool, so it takes the same connection
 * parameters you passed to `createDatabase`. [subscribe] holds one dedicated connection in `LISTEN`;
 * [publish] opens a short-lived connection per call to send `NOTIFY` (simple and leak-free — fine
 * for typical write rates; for very high write throughput use a broker transport instead).
 *
 * [channel] must be a valid SQL identifier; the default is fine.
 */
public fun postgresListenNotifyTransport(
    host: String,
    port: Int = 5432,
    database: String,
    user: String,
    password: String,
    channel: String = "kormium_changes",
): NotificationTransport = PgJdbcNotificationTransport(
    jdbcUrl = "jdbc:postgresql://$host:$port/$database",
    user = user,
    password = password,
    channel = channel,
)

private class PgJdbcNotificationTransport(
    private val jdbcUrl: String,
    private val user: String,
    private val password: String,
    private val channel: String,
) : NotificationTransport {

    override suspend fun publish(tables: Set<String>): Unit = withContext(Dispatchers.IO) {
        val payload = escapeSqlLiteral(encodeTablePayload(tables))
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            conn.createStatement().use { it.execute("NOTIFY $channel, '$payload'") }
        }
    }

    override fun subscribe(): Flow<Set<String>> = flow {
        val conn = DriverManager.getConnection(jdbcUrl, user, password)
        try {
            val pg = conn.unwrap(PGConnection::class.java)
            conn.createStatement().use { it.execute("LISTEN $channel") }
            while (currentCoroutineContext().isActive) {
                // Blocks up to 10s for a notification, then loops so cancellation is seen promptly.
                val notifications = pg.getNotifications(10_000)
                if (notifications != null) {
                    for (n in notifications) emit(decodeTablePayload(n.parameter))
                }
            }
        } finally {
            conn.close()
        }
    }.flowOn(Dispatchers.IO)
}
