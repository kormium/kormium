package io.github.kormium

// Escapes a payload for embedding in a single-quoted SQL string literal (NOTIFY chan, 'payload').
// The wire format itself (table-name set <-> string) is core's encodeTablePayload/decodeTablePayload,
// shared across all Postgres paths so JDBC/libpq/r2dbc instances interoperate on one channel.
internal fun escapeSqlLiteral(s: String): String = s.replace("'", "''")
