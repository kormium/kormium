package io.github.kormium

import io.github.kormium.postgres.async.asyncExecSimple
import io.github.kormium.postgres.async.createSocketReactor
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import libpq.ConnStatusType
import libpq.PGconn
import libpq.PQclear
import libpq.PQconnectdbParams
import libpq.PQconsumeInput
import libpq.PQerrorMessage
import libpq.PQexec
import libpq.PQfinish
import libpq.PQfreemem
import libpq.PQnotifies
import libpq.PQsetnonblocking
import libpq.PQsocket
import libpq.PQstatus

/**
 * A Postgres `LISTEN/NOTIFY` [NotificationTransport] for Kotlin/Native (libpq) — pass it to
 * `connectNotifications` to make commit notifications cross process boundaries with NO external
 * broker.
 *
 * It opens its OWN libpq connections, separate from the driver's pool. [subscribe] holds one
 * dedicated non-blocking connection in `LISTEN` and waits on the shared socket reactor (no thread
 * blocked); [publish] opens a short-lived blocking connection per call to `NOTIFY`. The wire format
 * is shared with the JDBC/r2dbc transports, so a native instance interoperates with them.
 *
 * Note: the socket reactor is unavailable on Windows native (mingwX64); [subscribe] there fails.
 */
public fun postgresListenNotifyTransport(
    host: String,
    port: Int = 5432,
    database: String,
    user: String,
    password: String,
    channel: String = "kormium_changes",
): NotificationTransport = PgLibpqNotificationTransport(host, port, database, user, password, channel)

@OptIn(ExperimentalForeignApi::class)
private class PgLibpqNotificationTransport(
    private val host: String,
    private val port: Int,
    private val database: String,
    private val user: String,
    private val password: String,
    private val channel: String,
) : NotificationTransport {

    override suspend fun publish(tables: Set<String>): Unit = withContext(Dispatchers.Default) {
        val payload = escapeSqlLiteral(encodeTablePayload(tables))
        val conn = openConnection(nonblocking = false)
        try {
            PQclear(PQexec(conn, "NOTIFY $channel, '$payload'"))
        } finally {
            PQfinish(conn)
        }
    }

    override fun subscribe(): Flow<Set<String>> = flow {
        val reactor = createSocketReactor()
            ?: error("Kormium native LISTEN/NOTIFY is unsupported on this target (no socket reactor)")
        reactor.start()
        val conn = openConnection(nonblocking = true)
        try {
            asyncExecSimple(conn, "LISTEN $channel", reactor)?.let { PQclear(it) }
            val sock = PQsocket(conn)
            while (currentCoroutineContext().isActive) {
                reactor.awaitReadable(sock)
                check(PQconsumeInput(conn) == 1) { "consume failed: " + PQerrorMessage(conn)?.toKString() }
                while (true) {
                    val note = PQnotifies(conn) ?: break
                    val payload = note.pointed.extra?.toKString().orEmpty()
                    PQfreemem(note)
                    emit(decodeTablePayload(payload))
                }
            }
        } finally {
            PQfinish(conn)
            reactor.close()
        }
    }

    private fun openConnection(nonblocking: Boolean): CPointer<PGconn> = memScoped {
        val keywords = listOf("host", "port", "dbname", "user", "password", "connect_timeout", "application_name")
        val values = listOf(host, port.toString(), database, user, password, "10", "kormium-notify")
        val keywordsArray = allocArrayOf(keywords.map { it.cstr.getPointer(this) } + listOf<CPointer<ByteVar>?>(null))
        val valuesArray = allocArrayOf(values.map { it.cstr.getPointer(this) } + listOf<CPointer<ByteVar>?>(null))
        val conn = PQconnectdbParams(keywordsArray, valuesArray, 0)
        requireNotNull(conn) { "Failed to allocate a Postgres connection" }
        if (PQstatus(conn) != ConnStatusType.CONNECTION_OK) {
            val message = PQerrorMessage(conn)?.toKString()?.trim().orEmpty()
            PQfinish(conn)
            error(message.ifEmpty { "Failed to connect to Postgres" })
        }
        if (nonblocking) PQsetnonblocking(conn, 1)
        conn
    }
}
