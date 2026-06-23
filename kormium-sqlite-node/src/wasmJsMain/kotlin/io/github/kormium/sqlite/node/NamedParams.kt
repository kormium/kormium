package io.github.kormium.sqlite.node

/** A SQL string with its `:name` placeholders rewritten to `?`, plus the names in order. */
internal class ParsedSql(val sql: String, val names: List<String>)

/** Rewrites korm's `:name` placeholders to SQLite's positional `?`, recording names in order. */
internal fun parseNamedParams(sql: String): ParsedSql {
    val out = StringBuilder(sql.length)
    val names = ArrayList<String>()
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        when {
            c == '\'' || c == '"' -> {
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
            c == ':' && i + 1 < sql.length && (sql[i + 1].isLetter() || sql[i + 1] == '_') -> {
                var j = i + 1
                while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                names.add(sql.substring(i + 1, j))
                out.append('?')
                i = j
            }
            else -> { out.append(c); i++ }
        }
    }
    return ParsedSql(out.toString(), names)
}
