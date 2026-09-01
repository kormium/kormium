package io.github.kormium.wasm.driver

/** Renders the positional bind marker for the 0-based parameter [index]. */
public typealias ParamMarker = (index: Int) -> String

/** `?` for every parameter (SQLite, MySQL). */
public val QuestionMarker: ParamMarker = { "?" }

/** `$1, $2, …` (Postgres; 1-based in the SQL, bound by 0-based index). */
public val DollarMarker: ParamMarker = { i -> "\$${i + 1}" }

/** A SQL string with its `:name` placeholders rewritten to [marker], plus the names in order. */
public class ParsedSql(public val sql: String, public val names: List<String>)

/**
 * Rewrites Kormium's Spring-style `:name` placeholders to the driver's positional marker and records
 * the names in occurrence order so values can be bound by index. Quoted literals (`'`, `"`, backtick),
 * line/block comments and Postgres `::` casts are copied verbatim. Shared by every Wasm engine —
 * only the [marker] differs.
 */
public fun parseNamedParams(sql: String, marker: ParamMarker): ParsedSql {
    val out = StringBuilder(sql.length)
    val names = ArrayList<String>()
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        when {
            c == '\'' || c == '"' || c == '`' -> {
                out.append(c); i++
                while (i < sql.length) {
                    val ch = sql[i]; out.append(ch); i++
                    if (ch == c) break
                }
            }
            c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                while (i < sql.length && sql[i] != '\n') { out.append(sql[i]); i++ }
            }
            c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                out.append("/*"); i += 2
                while (i < sql.length) {
                    if (sql[i] == '*' && i + 1 < sql.length && sql[i + 1] == '/') { out.append("*/"); i += 2; break }
                    out.append(sql[i]); i++
                }
            }
            c == ':' && i + 1 < sql.length && sql[i + 1] == ':' -> { out.append("::"); i += 2 }
            c == ':' && i + 1 < sql.length && (sql[i + 1].isLetter() || sql[i + 1] == '_') -> {
                var j = i + 1
                while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                names.add(sql.substring(i + 1, j))
                out.append(marker(names.size - 1))
                i = j
            }
            else -> { out.append(c); i++ }
        }
    }
    return ParsedSql(out.toString(), names)
}
