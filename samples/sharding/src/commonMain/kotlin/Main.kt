@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.samples.sharding

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.eq
import io.github.kormium.transaction
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// --- Two independent catalogs -------------------------------------------------
// A Catalog is a marker type. A Table is tagged with its catalog, and so is the
// Database it runs against, so the compiler rejects cross-catalog use (see note in main()).

object AccountsCatalog : Catalog
object AuditCatalog : Catalog

object Accounts : Table<AccountsCatalog, Account>("accounts", ::Account) {
    val id by Column.Int().primaryKey()
    val owner by Column.Text()
}

object AuditLog : Table<AuditCatalog, AuditEntry>("audit", ::AuditEntry) {
    val id by Column.Int().primaryKey()
    val message by Column.Text()
}

class Account : Entity() {
    var id by Accounts.id
    var owner by Accounts.owner
}

class AuditEntry : Entity() {
    var id by AuditLog.id
    var message by AuditLog.message
}

// --- Sharding: many Database<AccountsCatalog> instances of the SAME catalog ----
// Same catalog, multiple connections/databases; route each row to a shard by key.
class ShardedAccounts(private val shards: List<Database<AccountsCatalog>>) {
    private fun shardOf(id: Int) = id % shards.size

    // drop+create keeps the sample rerunnable (fixed ids would otherwise clash on a second run).
    fun createTables() = shards.forEach { it.autocommit { Accounts.execSql("DROP TABLE IF EXISTS \"accounts\""); Accounts.execSql(accountsDdl) } }

    fun put(account: Account): Int {
        val shard = shardOf(account.id!!)
        shards[shard].transaction { Accounts.insert(account) }
        return shard
    }

    fun get(id: Int): Account? = shards[shardOf(id)].autocommit { Accounts.findOne { where { Accounts.id eq id } } }

    fun countPerShard(): List<Long> = shards.map { it.autocommit { Accounts.count() } }
}

private fun account(id: Int, owner: String) = Account().apply { this.id = id; this.owner = owner }

fun main() {
    // Two shards for AccountsCatalog, each its OWN database. They must be distinct files:
    // Kormium opens ":memory:" in shared-cache mode, so two ":memory:" handles would be the same
    // database — not two shards. AuditCatalog is a separate (in-memory) database.
    val shardPaths = List(2) { Path(SystemTemporaryDirectory, "kormium-shard-$it.db") }
    val shards: List<Database<AccountsCatalog>> = shardPaths.map { createSqliteDatabase(it.toString()) }
    val auditDb: Database<AuditCatalog> = createSqliteDatabase()

    try {
        val accounts = ShardedAccounts(shards)
        accounts.createTables()
        auditDb.autocommit { AuditLog.execSql(auditDdl) }

        (1..6).forEach { id ->
            val shard = accounts.put(account(id, "owner-$id"))
            auditDb.transaction { AuditLog.insert(AuditEntry().apply { this.id = id; message = "created $id on shard $shard" }) }
        }

        println("rows per shard = ${accounts.countPerShard()}")     // even ids on shard 0, odd on shard 1
        println("get(5)         = ${accounts.get(5)?.owner} (shard ${5 % shards.size})")
        println("audit entries  = ${auditDb.autocommit { AuditLog.all() }.map { it.message }}")

        // Compile-time catalog safety — the next line would NOT compile, Accounts is AccountsCatalog:
        //     auditDb.autocommit { Accounts.all() }   // error: wrong catalog
    } finally {
        shards.forEach { it.close() }
        auditDb.close()
        // Best-effort cleanup of the shard files (WAL also leaves -wal / -shm sidecars).
        shardPaths.forEach { p ->
            listOf(p.toString(), "${p}-wal", "${p}-shm").forEach { f ->
                runCatching { SystemFileSystem.delete(Path(f), mustExist = false) }
            }
        }
    }
}

// Schema owned by the app, not Kormium.
internal val accountsDdl = """CREATE TABLE IF NOT EXISTS "accounts" ("id" INTEGER NOT NULL, "owner" TEXT NOT NULL, PRIMARY KEY ("id"))"""
internal val auditDdl = """CREATE TABLE IF NOT EXISTS "audit" ("id" INTEGER NOT NULL, "message" TEXT NOT NULL, PRIMARY KEY ("id"))"""
