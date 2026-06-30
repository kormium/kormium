import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.DatabaseClosedException
import io.github.kormium.Entity
import io.github.kormium.MySqlDriver
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.UniqueViolationException
import io.github.kormium.autocommit
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.eq
import io.github.kormium.transaction
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonPrimitive
import platform.posix.getenv
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * End-to-end tests for the native (libmysqlclient/MariaDB Connector/C) driver. They need a running
 * MySQL/MariaDB reachable at MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD/MYSQL_DB (defaults:
 * 127.0.0.1:3306, root, empty, kormium_test). When no server is reachable the suite skips itself
 * (the native test target has no Docker/Testcontainers, so this mirrors assumeDockerAvailable).
 */
@OptIn(ExperimentalForeignApi::class)
class NativeMySqlTest {

    private fun env(name: String, default: String): String = getenv(name)?.toKString() ?: default

    private fun connectOrNull(poolSize: Int = 4): MySqlDriver? = try {
        createDatabase(
            host = env("MYSQL_HOST", "127.0.0.1"),
            port = env("MYSQL_PORT", "3306").toInt(),
            database = env("MYSQL_DB", "kormium_test"),
            user = env("MYSQL_USER", "root"),
            password = env("MYSQL_PASSWORD", ""),
            poolSize = poolSize,
        )
    } catch (e: Throwable) {
        println("Skipping native MySQL test: ${e.message}")
        null
    }

    @Test
    fun lifecycleContract() {
        val driver = connectOrNull() ?: return
        assertEquals(false, driver.isClosed)
        driver.close()
        assertEquals(true, driver.isClosed)
        driver.close() // idempotent: must not throw
        assertFailsWith<DatabaseClosedException> { driver.autocommit { execute("SELECT 1") { rs -> rs.getInt(0) } } }
    }

    @Test
    fun allColumnTypesRoundTrip() {
        val driver = connectOrNull() ?: return
        val db: Database<NativeCatalog> = driver
        driver.use {
            val id = Uuid.random()
            val instant = Instant.parse("2024-01-02T03:04:05Z")
            val json = JsonPrimitive("hi")
            val date = LocalDate.parse("2024-01-02")
            val time = LocalTime.parse("03:04:05")
            val dateTime = LocalDateTime.parse("2024-01-02T03:04:05")
            db.transaction {
                NativeAllTypes.execSql(allTypesDdl)
                NativeAllTypes.deleteWhere(Query(NativeAllTypes.id eq id))
                NativeAllTypes.insert(NativeAllTypesEntity().apply {
                    this.id = id
                    this.anInt = 42
                    this.aDouble = 2.5
                    this.aBool = true
                    this.aText = "txt"
                    this.aDecimal = BigDecimal.fromInt(123)
                    this.anInstant = instant
                    this.aJson = json
                    this.aLong = 9_000_000_000L
                    this.aFloat = 1.5f
                    this.aShort = 7.toShort()
                    this.aDate = date
                    this.aTime = time
                    this.aDateTime = dateTime
                })
            }
            val row = db.autocommit { NativeAllTypes.findOne { where { NativeAllTypes.id eq id } } }!!
            assertEquals(42, row.anInt)
            assertEquals(2.5, row.aDouble)
            assertEquals(true, row.aBool)
            assertEquals("txt", row.aText)
            assertEquals(0, BigDecimal.fromInt(123).compareTo(row.aDecimal))
            assertEquals(instant, row.anInstant)
            assertEquals(json, row.aJson)
            assertEquals(9_000_000_000L, row.aLong)
            assertEquals(1.5f, row.aFloat)
            assertEquals(7.toShort(), row.aShort)
            assertEquals(date, row.aDate)
            assertEquals(time, row.aTime)
            assertEquals(dateTime, row.aDateTime)
            db.transaction { NativeAllTypes.deleteWhere(Query(NativeAllTypes.id eq id)) }
        }
    }

    @Test
    fun crudAndQuoteRoundTrip() {
        val driver = connectOrNull() ?: return
        val db: Database<NativeCatalog> = driver
        driver.use {
            db.transaction { NativeProducts.execSql(productsDdl) }
            val id = Uuid.random()
            val tricky = "O'Brien'; DROP TABLE x; --"
            db.transaction {
                NativeProducts.deleteWhere(Query(NativeProducts.id eq id))
                NativeProducts.insert(NativeProduct().apply {
                    this.id = id; this.qty = 5; this.name = tricky
                })
            }
            val found = db.autocommit { NativeProducts.findOne { where { NativeProducts.id eq id } } }
            assertEquals(5, found?.qty)
            assertEquals(tricky, found?.name)
            db.transaction {
                NativeProducts.update(Query(NativeProducts.id eq id), NativeProduct().apply { qty = 9 })
            }
            assertEquals(9, db.autocommit { NativeProducts.findOne { where { NativeProducts.id eq id } } }?.qty)
            db.transaction { NativeProducts.deleteWhere(Query(NativeProducts.id eq id)) }
        }
    }

    @Test
    fun returningInsertReSelectsByPk() {
        val driver = connectOrNull() ?: return
        val db: Database<NativeCatalog> = driver
        driver.use {
            db.transaction { NativeProducts.execSql(productsDdl) }
            val id = Uuid.random()
            db.transaction { NativeProducts.deleteWhere(Query(NativeProducts.id eq id)) }
            val returned = db.transaction {
                NativeProducts.insert(
                    NativeProduct().apply { this.id = id; this.qty = 7; this.name = "ret" },
                    returning = true,
                )
            }
            assertEquals(id, returned?.id)
            assertEquals("ret", returned?.name)
            db.transaction { NativeProducts.deleteWhere(Query(NativeProducts.id eq id)) }
        }
    }

    @Test
    fun upsertAndInsertOrIgnore() {
        val driver = connectOrNull() ?: return
        val db: Database<NativeCatalog> = driver
        driver.use {
            db.transaction { NativeProducts.execSql(productsDdl) }
            val id = Uuid.random()
            db.transaction {
                NativeProducts.deleteWhere(Query(NativeProducts.id eq id))
                NativeProducts.insert(NativeProduct().apply { this.id = id; this.qty = 1; this.name = "orig" })
            }
            db.transaction {
                NativeProducts.upsert(
                    NativeProduct().apply { this.id = id; this.qty = 1; this.name = "x" },
                    onConflict = NativeProducts.id,
                    update = NativeProduct().apply { this.qty = 99 },
                )
            }
            assertEquals(99, db.autocommit { NativeProducts.findOne { where { NativeProducts.id eq id } } }?.qty)

            db.transaction {
                NativeProducts.insertOrIgnore(
                    NativeProduct().apply { this.id = id; this.qty = 2; this.name = "dup" },
                    onConflict = NativeProducts.id,
                )
            }
            assertEquals("orig", db.autocommit { NativeProducts.findOne { where { NativeProducts.id eq id } } }?.name) // unchanged
            db.transaction { NativeProducts.deleteWhere(Query(NativeProducts.id eq id)) }
        }
    }

    @Test
    fun cyrillicRoundTrip() {
        val driver = connectOrNull() ?: return
        val db: Database<NativeCatalog> = driver
        driver.use {
            db.transaction { NativeProducts.execSql(productsDdl) }
            val id = Uuid.random()
            val text = "Привет, мир 🌍"
            db.transaction {
                NativeProducts.deleteWhere(Query(NativeProducts.id eq id))
                NativeProducts.insert(NativeProduct().apply { this.id = id; this.qty = 1; this.name = text })
            }
            assertEquals(text, db.autocommit { NativeProducts.findOne { where { NativeProducts.id eq id } } }?.name)
            db.transaction { NativeProducts.deleteWhere(Query(NativeProducts.id eq id)) }
        }
    }

    @Test
    fun typedUniqueViolation() {
        val driver = connectOrNull() ?: return
        val db: Database<NativeCatalog> = driver
        driver.use {
            db.transaction { NativeProducts.execSql(productsDdl) }
            val id = Uuid.random()
            db.transaction {
                NativeProducts.deleteWhere(Query(NativeProducts.id eq id))
                NativeProducts.insert(NativeProduct().apply { this.id = id; this.qty = 1; this.name = "dup" })
            }
            assertFailsWith<UniqueViolationException> {
                db.transaction {
                    NativeProducts.insert(NativeProduct().apply { this.id = id; this.qty = 2; this.name = "dup2" })
                }
            }
            db.transaction { NativeProducts.deleteWhere(Query(NativeProducts.id eq id)) }
        }
    }

    @Test
    fun transactionRollsBack() {
        val driver = connectOrNull() ?: return
        val db: Database<NativeCatalog> = driver
        driver.use {
            db.transaction { NativeProducts.execSql(productsDdl) }
            val id = Uuid.random()
            assertFailsWith<RuntimeException> {
                db.transaction {
                    NativeProducts.insert(NativeProduct().apply { this.id = id; this.qty = 1; this.name = "boom" })
                    throw RuntimeException("boom")
                }
            }
            assertEquals(null, db.autocommit { NativeProducts.findOne { where { NativeProducts.id eq id } } })
        }
    }
}

object NativeCatalog : Catalog

class NativeProduct : Entity() {
    var id by NativeProducts.id
    var qty by NativeProducts.qty
    var name by NativeProducts.name
}

object NativeProducts : Table<NativeCatalog, NativeProduct>("native_products", ::NativeProduct) {
    val id by Column.UUID()
    val qty by Column.Int()
    val name by Column.Text()

    init { id; qty; name }
}

class NativeAllTypesEntity : Entity() {
    var id by NativeAllTypes.id
    var anInt by NativeAllTypes.anInt
    var aDouble by NativeAllTypes.aDouble
    var aBool by NativeAllTypes.aBool
    var aText by NativeAllTypes.aText
    var aDecimal by NativeAllTypes.aDecimal
    var anInstant by NativeAllTypes.anInstant
    var aJson by NativeAllTypes.aJson
    var aLong by NativeAllTypes.aLong
    var aFloat by NativeAllTypes.aFloat
    var aShort by NativeAllTypes.aShort
    var aDate by NativeAllTypes.aDate
    var aTime by NativeAllTypes.aTime
    var aDateTime by NativeAllTypes.aDateTime
}

object NativeAllTypes : Table<NativeCatalog, NativeAllTypesEntity>("native_all_types", ::NativeAllTypesEntity) {
    val id by Column.UUID()
    val anInt by Column.Int()
    val aDouble by Column.Double()
    val aBool by Column.Boolean()
    val aText by Column.Text()
    val aDecimal by Column.BigDecimal()
    val anInstant by Column.Instant()
    val aJson by Column.Json()
    val aLong by Column.Long()
    val aFloat by Column.Float()
    val aShort by Column.Short()
    val aDate by Column.LocalDate()
    val aTime by Column.LocalTime()
    val aDateTime by Column.LocalDateTime()

    init {
        id; anInt; aDouble; aBool; aText; aDecimal; anInstant; aJson
        aLong; aFloat; aShort; aDate; aTime; aDateTime
    }
}

private val productsDdl =
    "CREATE TABLE IF NOT EXISTS `native_products` (`id` CHAR(36) NOT NULL, `qty` INT NOT NULL, `name` VARCHAR(255) NOT NULL, PRIMARY KEY (`id`))"

private val allTypesDdl =
    "CREATE TABLE IF NOT EXISTS `native_all_types` (`id` CHAR(36) NOT NULL, `anInt` INT NOT NULL, `aDouble` DOUBLE NOT NULL, `aBool` BOOLEAN NOT NULL, `aText` TEXT NOT NULL, `aDecimal` DECIMAL(20,4) NOT NULL, `anInstant` TIMESTAMP NOT NULL, `aJson` JSON NOT NULL, `aLong` BIGINT NOT NULL, `aFloat` FLOAT NOT NULL, `aShort` SMALLINT NOT NULL, `aDate` DATE NOT NULL, `aTime` TIME NOT NULL, `aDateTime` DATETIME NOT NULL, PRIMARY KEY (`id`))"
