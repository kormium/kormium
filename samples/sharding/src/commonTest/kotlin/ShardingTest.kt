@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.samples.sharding

import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.transaction
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs on both JVM and native; shards are distinct SQLite files in the temp dir. */
class ShardingTest {

    @Test
    fun shardingAndCatalogs() {
        val tag = Random.nextInt(0, Int.MAX_VALUE)
        val shardPaths = List(2) { Path(SystemTemporaryDirectory, "kormium-shard-test-$tag-$it.db") }
        val shards: List<Database<AccountsCatalog>> = shardPaths.map { createSqliteDatabase(it.toString()) }
        val auditDb: Database<AuditCatalog> = createSqliteDatabase()

        try {
            val accounts = ShardedAccounts(shards)
            accounts.createTables()
            auditDb.autocommit { AuditLog.execSql(auditDdl) }

            (1..6).forEach { id ->
                accounts.put(Account().apply { this.id = id; owner = "owner-$id" })
                auditDb.transaction { AuditLog.insert(AuditEntry().apply { this.id = id; message = "created $id" }) }
            }

            assertEquals(listOf(3L, 3L), accounts.countPerShard()) // even ids -> shard 0, odd -> shard 1
            assertEquals("owner-5", accounts.get(5)?.owner)
            assertEquals(1, 5 % shards.size)
            assertEquals(6L, auditDb.autocommit { AuditLog.count() })
        } finally {
            shards.forEach { it.close() }
            auditDb.close()
            shardPaths.forEach { p ->
                listOf(p.toString(), "$p-wal", "$p-shm").forEach { f ->
                    runCatching { SystemFileSystem.delete(Path(f), mustExist = false) }
                }
            }
        }
    }
}
