@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.samples.sqlitecache

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.eq
import io.github.kormium.transaction

// Two catalogs: the same "products" shape lives in Postgres (source of truth) and in a
// local SQLite cache. Each catalog has its own table + entity, tagged so they can't be mixed.

object PgCatalog : Catalog
object CacheCatalog : Catalog

/** The platform-agnostic domain object the app actually works with. */
data class Product(val id: Int, val name: String)

object PgProducts : Table<PgCatalog, PgProduct>("products", ::PgProduct) {
    val id by Column.Int().primaryKey()
    val name by Column.Text()
}

object CachedProducts : Table<CacheCatalog, CacheProduct>("products", ::CacheProduct) {
    val id by Column.Int().primaryKey()
    val name by Column.Text()
}

class PgProduct : Entity() {
    var id by PgProducts.id
    var name by PgProducts.name
}

class CacheProduct : Entity() {
    var id by CachedProducts.id
    var name by CachedProducts.name
}

private fun PgProduct.toProduct() = Product(id!!, name!!)
private fun Product.toCacheRow() = CacheProduct().apply { id = this@toCacheRow.id; name = this@toCacheRow.name }
private fun CacheProduct.toProduct() = Product(id!!, name!!)

/** Read-through cache: look in SQLite first, fall back to Postgres on a miss and populate the cache. */
class ProductRepository(
    private val pg: Database<PgCatalog>,
    private val cache: Database<CacheCatalog>,
) {
    fun get(id: Int): Product? {
        cache.autocommit { CachedProducts.findOne { where { CachedProducts.id eq id } } }?.let {
            println("cache HIT  $id")
            return it.toProduct()
        }
        val fromPg = pg.autocommit { PgProducts.findOne { where { PgProducts.id eq id } } }?.toProduct()
        if (fromPg == null) {
            println("cache MISS $id (not in Postgres)")
            return null
        }
        println("cache MISS $id -> populate from Postgres")
        cache.transaction { CachedProducts.insert(fromPg.toCacheRow()) }
        return fromPg
    }
}

fun main() {
    val pg: Database<PgCatalog> = createDatabase(
        host = "localhost",
        port = 5432,
        database = "postgres",
        user = "postgres",
        password = "password",
    )
    val cache: Database<CacheCatalog> = createSqliteDatabase() // in-memory local cache

    pg.use {
        cache.use {
            // Seed Postgres (the source of truth).
            pg.transaction {
                PgProducts.execSql("DROP TABLE IF EXISTS \"products\"")
                PgProducts.execSql(pgProductsDdl)
                PgProducts.insert(PgProduct().apply { id = 1; name = "Keyboard" })
                PgProducts.insert(PgProduct().apply { id = 2; name = "Mouse" })
            }
            cache.autocommit { CachedProducts.execSql(cachedProductsDdl) }

            val repo = ProductRepository(pg, cache)
            println("get(1) = ${repo.get(1)?.name}") // MISS -> populate
            println("get(1) = ${repo.get(1)?.name}") // HIT
            println("get(2) = ${repo.get(2)?.name}") // MISS -> populate
            println("get(9) = ${repo.get(9)?.name}") // MISS, absent everywhere
        }
    }
}

// Schema owned by the app, not Kormium. PgProducts is Postgres; CachedProducts is SQLite.
internal val pgProductsDdl = """CREATE TABLE IF NOT EXISTS "products" ("id" integer NOT NULL, "name" text NOT NULL, PRIMARY KEY ("id"))"""
internal val cachedProductsDdl = """CREATE TABLE IF NOT EXISTS "products" ("id" INTEGER NOT NULL, "name" TEXT NOT NULL, PRIMARY KEY ("id"))"""
