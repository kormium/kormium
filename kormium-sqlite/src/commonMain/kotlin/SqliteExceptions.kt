package io.github.kormium

// SQLite extended result codes for the constraint violations korm distinguishes.
// https://www.sqlite.org/rescode.html
private const val SQLITE_CONSTRAINT_CHECK = 275
private const val SQLITE_CONSTRAINT_FOREIGNKEY = 787
private const val SQLITE_CONSTRAINT_NOTNULL = 1299
private const val SQLITE_CONSTRAINT_PRIMARYKEY = 1555
private const val SQLITE_CONSTRAINT_UNIQUE = 2067

/**
 * Maps a SQLite failure to a typed core [QueryException]. SQLite has no SQLSTATE, so we
 * key off the extended result code when available and otherwise off the human-readable
 * message text (stable across the JDBC and native drivers, e.g. "UNIQUE constraint
 * failed"). The extended code (when known) is carried in [QueryException.sqlState].
 */
internal fun sqliteException(message: String, extendedCode: Int? = null, cause: Throwable? = null): QueryException {
    val state = extendedCode?.toString()
    return when {
        extendedCode == SQLITE_CONSTRAINT_UNIQUE || extendedCode == SQLITE_CONSTRAINT_PRIMARYKEY ||
            message.contains("UNIQUE constraint failed") || message.contains("PRIMARY KEY constraint failed") ->
            UniqueViolationException(message, state, cause)

        extendedCode == SQLITE_CONSTRAINT_FOREIGNKEY || message.contains("FOREIGN KEY constraint failed") ->
            ForeignKeyViolationException(message, state, cause)

        extendedCode == SQLITE_CONSTRAINT_NOTNULL || message.contains("NOT NULL constraint failed") ->
            NotNullViolationException(message, state, cause)

        extendedCode == SQLITE_CONSTRAINT_CHECK || message.contains("CHECK constraint failed") ->
            CheckViolationException(message, state, cause)

        else -> QueryException(message, state, cause)
    }
}
