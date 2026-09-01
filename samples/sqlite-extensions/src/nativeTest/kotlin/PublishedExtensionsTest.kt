@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.samples.sqliteextensions

import io.github.kormium.QueryException
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.sqlite.regexp.SqliteRegexp
import io.github.kormium.sqlite.series.SqliteSeries
import io.github.kormium.sqlite.uuid.SqliteUuid
import io.github.kormium.sqlite.vec.SqliteVec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Four extension packages, resolved from Maven Central, in one database.
 *
 * Each carries only itself — a static library whose `sqlite3_*` symbols stay unresolved until the
 * final link, where they are satisfied by the one SQLite inside `kormium-sqlite`. If that were not
 * so this binary would not link, or would carry several SQLites and behave strangely. It does
 * neither, and nothing in this module knows any extension's entry point.
 *
 * One test method rather than several: registration is process-global, so the negative control is
 * only meaningful before the first one.
 */
class PublishedExtensionsTest {

    @Test
    fun fourPublishedExtensionsComposeInOneDatabase() {
        // Nothing registered yet: a plain database has no idea what vec0 is.
        createSqliteDatabase().use { db ->
            assertFailsWith<QueryException> {
                db.autocommit { execute("select vec_version()", emptyMap(), emptyList()) { it.getString(0) } }
            }
        }

        // poolSize = 3: every connection the pool opens must see all four.
        createSqliteDatabase(poolSize = 3) {
            sqlite {
                extension(SqliteVec)
                extension(SqliteUuid)
                extension(SqliteRegexp)
                extension(SqliteSeries)
            }
        }.use { db ->
            // generate_series builds the rows, uuid() keys them, vec0 indexes them, regexp filters.
            db.autocommit {
                executeUpdate("create virtual table vectors using vec0(embedding float[2])", emptyMap(), emptyList())
                executeUpdate("create table docs(id integer primary key, uid text, label text)", emptyMap(), emptyList())
                executeUpdate(
                    "insert into docs(id, uid, label) " +
                        "select value, uuid(), 'doc-' || value from generate_series(1, 8)",
                    emptyMap(),
                    emptyList(),
                )
                executeUpdate(
                    "insert into vectors(rowid, embedding) " +
                        "select value, '[' || (value / 10.0) || ', 0.5]' from generate_series(1, 8)",
                    emptyMap(),
                    emptyList(),
                )
            }

            val rows = db.autocommit {
                execute("select count(*) from docs", emptyMap(), emptyList()) { it.getLong(0) }
            }.single()
            assertEquals(8L, rows, "generate_series should have produced eight rows")

            val uid = db.autocommit {
                execute("select uid from docs where id = 1", emptyMap(), emptyList()) { it.getString(0) }
            }.single()
            assertEquals(36, uid!!.length, "uuid() should have produced a 36-character UUID")

            val matching = db.autocommit {
                execute(
                    "select count(*) from docs where label regexp '^doc-[1-3]$'",
                    emptyMap(),
                    emptyList(),
                ) { it.getLong(0) }
            }.single()
            assertEquals(3L, matching, "the REGEXP operator should have matched doc-1..doc-3")

            // All four in one statement: nearest vector, its uuid, filtered by regexp.
            val nearest = db.autocommit {
                execute(
                    "select d.uid, v.distance from vectors v join docs d on d.id = v.rowid " +
                        "where v.embedding match '[0.15, 0.5]' and v.k = 3 and d.label regexp '^doc-[0-9]+$' " +
                        "order by v.distance limit 1",
                    emptyMap(),
                    emptyList(),
                ) { it.getString(0) to it.getDouble(1) }
            }.single()
            assertEquals(36, nearest.first!!.length)
            assertTrue(nearest.second!! >= 0.0, "distance should be a real number, was ${nearest.second}")
        }
    }
}
