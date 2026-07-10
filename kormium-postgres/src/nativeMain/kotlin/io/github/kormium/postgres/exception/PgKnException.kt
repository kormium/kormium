package io.github.kormium.postgres.exception

public sealed class SQLException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

public class InvalidDataAccessApiUsageException(message: String, cause: Throwable? = null) : SQLException(message, cause)

public class AnonymousClassException : SQLException("Class must not be anonymous", null)

public class GetColumnValueException(columnIndex: Int) : SQLException("Error getting column $columnIndex value", null)

/** Raised when a statement (or the initial connection) fails on the server. */
public class QueryExecutionException(message: String, cause: Throwable? = null) : SQLException(message, cause)

/** Raised when the driver is used after [io.github.kormium.postgres.PostgresDriver] was closed. */
public class ConnectionClosedException : SQLException("PostgresDriver is closed", null)
