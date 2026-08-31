package io.github.kormium.jdbc

import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * A minimal [DataSource] over [DriverManager] that runs [onConnection] on every connection it
 * opens, before handing it to the pool.
 *
 * HikariCP has no per-connection callback: the only hook it offers is `connectionInitSql`, a
 * single SQL string. That is not enough for work that needs real code — loading a SQLite extension,
 * for instance — and it has to happen on *every* connection the pool opens, including the ones it
 * silently recreates when `maxLifetime` expires or a connection dies. Giving Hikari a DataSource we
 * own is the supported way to get that hook.
 *
 * Only used when a caller actually passes `onConnection`; otherwise [JdbcDatabase] keeps handing
 * Hikari a plain `jdbcUrl` as before.
 */
internal class InitializingDataSource(
    private val jdbcUrl: String,
    private val username: String?,
    private val password: String?,
    private val onConnection: (Connection) -> Unit,
) : DataSource {

    override fun getConnection(): Connection = open(username, password)

    override fun getConnection(username: String?, password: String?): Connection = open(username, password)

    private fun open(user: String?, pass: String?): Connection {
        val connection = if (user == null && pass == null) {
            DriverManager.getConnection(jdbcUrl)
        } else {
            DriverManager.getConnection(jdbcUrl, Properties().apply {
                if (user != null) setProperty("user", user)
                if (pass != null) setProperty("password", pass)
            })
        }
        // A connection that cannot be initialised must not reach the pool: close it and let the
        // failure propagate, so a broken extension or pragma fails the call that opened it.
        return try {
            onConnection(connection)
            connection
        } catch (e: Throwable) {
            runCatching { connection.close() }
            throw e
        }
    }

    override fun getLogWriter(): PrintWriter? = DriverManager.getLogWriter()
    override fun setLogWriter(out: PrintWriter?) = DriverManager.setLogWriter(out)
    override fun setLoginTimeout(seconds: Int) = DriverManager.setLoginTimeout(seconds)
    override fun getLoginTimeout(): Int = DriverManager.getLoginTimeout()
    override fun getParentLogger(): Logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> unwrap(iface: Class<T>): T =
        if (iface.isInstance(this)) this as T else throw java.sql.SQLException("not a wrapper for $iface")

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
