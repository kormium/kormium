@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.samples.sqlitevec

import io.github.kormium.QueryException
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.samples.sqliteuuid.SqliteUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end proof that two independent extension packages — each with its own klib, cinterop and
 * static library — compose in one process, one pool and one query.
 *
 * One test method, not several: static registration is process-global, so the negative control is
 * only meaningful before the first registration and the steps must run in a known order.
 */
class SqliteExtensionsSampleTest {

    @Test
    fun twoExtensionPackagesComposeInOneDatabase() {
        // Neither extension exists in this process yet.
        createSqliteDatabase().use { db ->
            assertFailsWith<QueryException> {
                db.autocommit { execute("select vec_version()", emptyMap(), emptyList()) { it.getString(0) } }
            }
            assertFailsWith<QueryException> {
                db.autocommit { execute("select uuid()", emptyMap(), emptyList()) { it.getString(0) } }
            }
        }

        // poolSize = 3: every connection the pool opens must see both extensions.
        createSqliteDatabase(poolSize = 3) {
            sqlite {
                extension(SqliteVec)
                extension(SqliteUuid)
            }
        }.use { db ->
            val version = db.autocommit {
                execute("select vec_version()", emptyMap(), emptyList()) { it.getString(0) }
            }.single()
            assertTrue(version!!.startsWith("v0."), "got $version")

            val uuid = db.autocommit {
                execute("select uuid()", emptyMap(), emptyList()) { it.getString(0) }
            }.single()
            assertEquals(36, uuid!!.length)

            // Both extensions in one statement: a KNN query over a vec0 virtual table joined to an
            // ordinary table whose key came from uuid().
            db.autocommit {
                executeUpdate("create virtual table items using vec0(embedding float[2])", emptyMap(), emptyList())
                executeUpdate("create table keys(id integer primary key, uid text)", emptyMap(), emptyList())
                executeUpdate("insert into items(rowid, embedding) values (1, '[1.0, 0.0]')", emptyMap(), emptyList())
                executeUpdate("insert into keys(id, uid) values (1, uuid())", emptyMap(), emptyList())
            }
            val hit = db.autocommit {
                execute(
                    "select k.uid, i.distance from items i join keys k on k.id = i.rowid " +
                        "where i.embedding match '[0.9, 0.1]' and i.k = 1",
                    emptyMap(),
                    emptyList(),
                ) { it.getString(0) to it.getDouble(1) }
            }.single()
            assertEquals(36, hit.first!!.length)
            assertTrue(hit.second!! > 0.0)
        }
    }
}
