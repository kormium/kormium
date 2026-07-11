package io.github.kormium.mysql.exception

import io.github.kormium.KormiumException
import io.github.kormium.QueryException
import io.github.kormium.mysqlVendorException

/** Raised when a statement (or the initial connection) fails on the server. */
public class QueryExecutionException(message: String, cause: Throwable? = null) : KormiumException(message, cause)

/** Raised when the native driver is used after it was closed. */
public class ConnectionClosedException : KormiumException("MySqlDriver is closed")

/**
 * Maps a MySQL/MariaDB vendor error number (`mysql_stmt_errno`) to the most specific core
 * [QueryException] subtype via the shared [mysqlVendorException] table. [sqlState] is the server's
 * 5-character SQLSTATE (`mysql_stmt_sqlstate`) when known.
 */
public fun mysqlException(message: String, errno: UInt, sqlState: String? = null): QueryException =
    mysqlVendorException(message, errno.toInt(), sqlState)
