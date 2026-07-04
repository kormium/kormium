package io.github.kormium

import io.github.kormium.jdbc.SqlExceptionTranslator

/**
 * MySQL/MariaDB collapse every integrity violation onto SQLSTATE `23000`, so the standard
 * SQLSTATE-based translation can't tell a unique violation from a foreign-key one. We therefore
 * branch on the vendor error code (`SQLException.getErrorCode`) via the shared [mysqlVendorException]
 * table.
 */
public val MySqlExceptionTranslator: SqlExceptionTranslator = { e ->
    mysqlVendorException(e.message ?: "SQL error", e.errorCode, e.sqlState, e)
}
