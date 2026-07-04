package io.github.kormium

/**
 * Maps a MySQL/MariaDB **vendor error code** to the most specific core [QueryException] subtype.
 *
 * MySQL reports every integrity violation under SQLSTATE `23000`, so the standard SQLSTATE mapping
 * ([sqlException]) can't tell them apart — the vendor code is the authoritative discriminator. This
 * single table is shared by all three MySQL backends: the JVM JDBC translator (`getErrorCode`), the
 * native driver (`mysql_stmt_errno`) and the r2dbc translator (`R2dbcException.errorCode`).
 *
 * Codes (common to MySQL 8 and MariaDB): 1062/1586 duplicate entry, 1451/1452 foreign key,
 * 1048/1364 NOT NULL (column cannot be null / has no default), 3819 CHECK (MySQL 8.0.16+ /
 * MariaDB 10.2+). Unknown codes fall back to the standard SQLSTATE mapping.
 */
public fun mysqlVendorException(
    message: String,
    vendorCode: Int,
    sqlState: String? = null,
    cause: Throwable? = null,
): QueryException = when (vendorCode) {
    1062, 1586 -> UniqueViolationException(message, sqlState, cause)
    1451, 1452 -> ForeignKeyViolationException(message, sqlState, cause)
    1048, 1364 -> NotNullViolationException(message, sqlState, cause)
    3819 -> CheckViolationException(message, sqlState, cause)
    else -> sqlException(message, sqlState, cause)
}
