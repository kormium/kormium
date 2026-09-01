package io.github.kormium.mysql

/** A SQL string with its `:name` placeholders rewritten to MySQL `?`, plus the names in order. */
internal class NamedSql(val sql: String, val names: List<String>)

/**
 * Rewrites Kormium's `:name` placeholders to MySQL positional `?` and records the names in occurrence
 * order so values bind by index. Quoted strings/identifiers (`'…'`, `"…"`, `` `…` ``) and `--` /
 * `/* */` comments are copied verbatim so a `:` inside them is not treated as a parameter. MySQL has
 * no `::` cast operator, so (unlike the Postgres parser) there is no special-casing for it.
 */
internal fun parseNamed(sql: String): NamedSql {
    val out = StringBuilder(sql.length)
    val names = ArrayList<String>()
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        when {
            c == '\'' || c == '"' || c == '`' -> {
                out.append(c)
                i++
                while (i < sql.length) {
                    val ch = sql[i]
                    out.append(ch)
                    i++
                    if (ch == c) {
                        // A doubled quote is an escape inside the literal, not its end.
                        if (i < sql.length && sql[i] == c) {
                            out.append(sql[i]); i++; continue
                        }
                        break
                    }
                }
            }
            c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                while (i < sql.length && sql[i] != '\n') { out.append(sql[i]); i++ }
            }
            c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                out.append("/*"); i += 2
                while (i < sql.length) {
                    if (sql[i] == '*' && i + 1 < sql.length && sql[i + 1] == '/') {
                        out.append("*/"); i += 2; break
                    }
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
    return NamedSql(out.toString(), names)
}
