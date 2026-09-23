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
 * MariaDB 10.2+), 3572 NOWAIT refused and 1205 lock wait timeout. Unknown codes fall back to the
 * standard SQLSTATE mapping.
 *
 * The two lock codes both come back under SQLSTATE `HY000`, so only the vendor code can tell them
 * from anything else; PostgreSQL folds the same two situations into `55P03`, which is why both map
 * to one [LockNotAvailableException]. A deadlock (1213) is deliberately absent: MySQL reports it
 * under SQLSTATE `40001`, where the fallback already maps it to [ConcurrencyConflictException] —
 * that one rolls back the whole transaction, these two only the statement.
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
    3572, 1205 -> LockNotAvailableException(message, sqlState, cause)
    else -> sqlException(message, sqlState, cause)
}
