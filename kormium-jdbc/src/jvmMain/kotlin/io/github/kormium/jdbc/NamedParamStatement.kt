package io.github.kormium.jdbc

import io.github.kormium.SqlParameterSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * A [PreparedStatement] wrapper that accepts Spring-style `:name` named
 * parameters. On construction the SQL is parsed: every `:name` placeholder is
 * replaced by a positional `?` and the parameter order is recorded so values can
 * later be bound by name. `::` casts (Postgres) and quoted string literals are left
 * untouched, so the parser is backend-agnostic and shared by every JDBC backend.
 */
public class NamedParamStatement(conn: Connection, sql: String) : AutoCloseable {
    public val preparedStatement: PreparedStatement
    private val fields: List<String>

    init {
        // Parsing (`:name` -> `?` plus the parameter order) depends only on the SQL text,
        // which is identical across repeated calls of the same statement — cache it.
        val parsed = cachedParse(sql)
        fields = parsed.fields
        preparedStatement = conn.prepareStatement(parsed.sql)
    }

    @Throws(SQLException::class)
    override fun close() {
        preparedStatement.close()
    }

    @Throws(SQLException::class)
    public fun executeQuery(): java.sql.ResultSet {
        return preparedStatement.executeQuery()
    }

    @Throws(SQLException::class)
    public fun executeUpdate(): Int {
        return preparedStatement.executeUpdate()
    }

    /** Binds every named placeholder in the statement from [paramSource], by name. */
    public fun bind(paramSource: SqlParameterSource) {
        for (name in fields.toSet()) {
            require(paramSource.hasValue(name)) { "No value supplied for parameter \"$name\"" }
            setAny(name, paramSource.getValue(name))
        }
    }

    public fun setAny(name: String, value: Any?) {
        for (index in indexesOf(name)) {
            when (value) {
                null -> preparedStatement.setObject(index, null)
                is Boolean -> preparedStatement.setBoolean(index, value)
                is Byte -> preparedStatement.setByte(index, value)
                is Short -> preparedStatement.setShort(index, value)
                is Int -> preparedStatement.setInt(index, value)
                is Long -> preparedStatement.setLong(index, value)
                is Float -> preparedStatement.setFloat(index, value)
                is Double -> preparedStatement.setDouble(index, value)
                is java.sql.Date -> preparedStatement.setDate(index, value)
                is java.sql.Time -> preparedStatement.setTime(index, value)
                is java.sql.Timestamp -> preparedStatement.setTimestamp(index, value)
                is String -> preparedStatement.setString(index, value)
                else -> preparedStatement.setObject(index, value)
            }
        }
    }

    // 1-based JDBC indexes of every placeholder that used this name.
    private fun indexesOf(name: String): List<Int> =
        fields.mapIndexedNotNull { index, field -> if (field == name) index + 1 else null }

    private class Parsed(val sql: String, val fields: List<String>)

    public companion object {
        // Reparsing is one cheap pass over the SQL string; the cache only saves it for hot,
        // repeated statements. Bound it so one-off SQL cannot grow it without limit: an
        // access-order LRU capped at MAX_CACHE_ENTRIES, and SQL longer than
        // MAX_CACHEABLE_SQL_LENGTH bypasses the cache entirely — batch INSERTs and large
        // IN-lists embed a per-call number of placeholders, so each variant is a distinct
        // key that would only churn the cache.
        internal const val MAX_CACHE_ENTRIES = 1024
        internal const val MAX_CACHEABLE_SQL_LENGTH = 4096

        // accessOrder = true mutates the map structurally on reads, so every access —
        // including lookups — must hold the lock.
        private val parseCache = object : LinkedHashMap<String, Parsed>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Parsed>): Boolean =
                size > MAX_CACHE_ENTRIES
        }

        private fun cachedParse(sql: String): Parsed =
            if (sql.length > MAX_CACHEABLE_SQL_LENGTH) parse(sql)
            else synchronized(parseCache) { parseCache.getOrPut(sql) { parse(sql) } }

        internal val parseCacheSize: Int get() = synchronized(parseCache) { parseCache.size }

        // Visible for tests: exercises the cache exactly like the constructor does.
        internal fun parseCached(sql: String) {
            cachedParse(sql)
        }

        private fun parse(sql: String): Parsed {
            val out = StringBuilder(sql.length)
            val fields = ArrayList<String>()
            var i = 0
            while (i < sql.length) {
                val c = sql[i]
                when {
                    c == '\'' || c == '"' -> {
                        // Copy a quoted literal verbatim so ':' inside it is not treated as a parameter.
                        out.append(c)
                        i++
                        while (i < sql.length) {
                            val ch = sql[i]
                            out.append(ch)
                            i++
                            if (ch == c) break
                        }
                    }
                    c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                        // Single-line comment: copy verbatim to end of line so ':name' in it
                        // is not treated as a parameter.
                        while (i < sql.length && sql[i] != '\n') {
                            out.append(sql[i])
                            i++
                        }
                    }
                    c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                        // Block comment: copy verbatim through the closing "*/" (or to the
                        // end if unterminated) so ':name' inside it is not a parameter.
                        out.append("/*")
                        i += 2
                        while (i < sql.length) {
                            if (sql[i] == '*' && i + 1 < sql.length && sql[i + 1] == '/') {
                                out.append("*/")
                                i += 2
                                break
                            }
                            out.append(sql[i])
                            i++
                        }
                    }
                    c == ':' && i + 1 < sql.length && sql[i + 1] == ':' -> {
                        // Postgres "::" cast operator, not a parameter.
                        out.append("::")
                        i += 2
                    }
                    c == ':' && i + 1 < sql.length && (sql[i + 1].isLetter() || sql[i + 1] == '_') -> {
                        var j = i + 1
                        while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                        fields.add(sql.substring(i + 1, j))
                        out.append('?')
                        i = j
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return Parsed(out.toString(), fields)
        }
    }
}
