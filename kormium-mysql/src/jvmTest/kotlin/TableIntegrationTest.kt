@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.DatabaseClosedException
import io.github.kormium.Entity
import io.github.kormium.ForeignKeyViolationException
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.UniqueViolationException
import io.github.kormium.autocommit
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.eq
import io.github.kormium.migrate.Migration
import io.github.kormium.migrate.migrate
import io.github.kormium.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MySQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * End-to-end tests for the JVM MySQL driver against a real MySQL (Testcontainers). Mirrors the
 * Postgres [TableIntegrationTest], adapted to MySQL DDL (backtick quoting, CHAR(36) UUIDs, JSON
 * type) and exercising the MySQL-specific LIMIT/OFFSET rendering and vendor-code exception mapping.
 */
class TableIntegrationTest {

    @Test
    fun testInsertFindUpdateDeleteRoundTrip() {
        assumeDockerAvailable()
        val id = Uuid.random()
        ItDatabase.transaction {
            ItProducts.insert(ItProduct().apply {
                this.id = id
                this.price = BigDecimal.fromInt(100)
                this.qty = 5
                this.displayName = "widget"
                this.note = null
                this.rank = null
            })

            val found = ItProducts.findOne { where { ItProducts.id eq id } }
            assertEquals(id, found?.id)
            assertEquals(5, found?.qty)
            assertEquals("widget", found?.displayName)
            assertNull(found?.note)
            // Regression: a nullable numeric column that is NULL must read back as null, not 0.
            assertNull(found?.rank)
            assertEquals(0, BigDecimal.fromInt(100).compareTo(found?.price!!))

            ItProducts.update(ItProduct().apply { this.qty = 9 }, Query(ItProducts.id eq id))
            assertEquals(9, ItProducts.findOne { where { ItProducts.id eq id } }?.qty)

            ItProducts.deleteWhere(Query(ItProducts.id eq id))
            assertNull(ItProducts.findOne { where { ItProducts.id eq id } })
        }
    }

    /** A value containing a quote round-trips intact, proving end-to-end parameterization. */
    @Test
    fun testValueWithQuoteRoundTrips() {
        assumeDockerAvailable()
        val id = Uuid.random()
        val tricky = "O'Brien'; DROP TABLE it_products; --"
        ItDatabase.transaction {
            ItProducts.insert(ItProduct().apply {
                this.id = id
                this.price = BigDecimal.fromInt(1)
                this.qty = 1
                this.displayName = tricky
                this.note = null
            })
            assertEquals(tricky, ItProducts.findOne { where { ItProducts.id eq id } }?.displayName)
        }
    }

    /** MySQL LIMIT/OFFSET: both a real limit+offset and an offset-without-limit must render valid SQL. */
    @Test
    fun testLimitOffsetRendersValidMySql() {
        assumeDockerAvailable()
        val ids = List(5) { Uuid.random() }
        ItDatabase.transaction {
            ItProducts.insertAll(ids.mapIndexed { i, id ->
                ItProduct().apply {
                    this.id = id; this.price = BigDecimal.fromInt(i); this.qty = i
                    this.displayName = "page"; this.note = null; this.rank = null
                }
            })
        }
        // LIMIT 2 OFFSET 1 ordered by qty -> rows 1,2.
        val page = ItDatabase.autocommit {
            ItProducts.find {
                where { ItProducts.displayName eq "page" }
                orderBy ASC ItProducts.qty
                limit = 2
                offset = 1
            }
        }
        assertEquals(listOf(1, 2), page.map { it.qty })

        // OFFSET without a real LIMIT: MySQL needs a sentinel LIMIT, supplied by MySqlDialect.
        val tail = ItDatabase.autocommit {
            ItProducts.find {
                where { ItProducts.displayName eq "page" }
                orderBy ASC ItProducts.qty
                offset = 2
            }
        }
        assertEquals(listOf(2, 3, 4), tail.map { it.qty })

        ItDatabase.transaction { ids.forEach { ItProducts.deleteWhere(Query(ItProducts.id eq it)) } }
    }

    /** insert(returning=true): MySQL has no RETURNING, so the row is re-selected by PK. */
    @Test
    fun testReturningInsertReSelectsRow() {
        assumeDockerAvailable()
        val id = Uuid.random()
        val returned = ItDatabase.transaction {
            ItProducts.insert(
                ItProduct().apply {
                    this.id = id; this.price = BigDecimal.fromInt(7); this.qty = 7
                    this.displayName = "ret"; this.note = null; this.rank = null
                },
                returning = true,
            )
        }
        assertEquals(id, returned?.id)
        assertEquals("ret", returned?.displayName)
        ItDatabase.transaction { ItProducts.deleteWhere(Query(ItProducts.id eq id)) }
    }

    /** upsert on a PK conflict updates the existing row (MySQL ON DUPLICATE KEY UPDATE). */
    @Test
    fun testUpsertOnConflictUpdates() {
        assumeDockerAvailable()
        val id = Uuid.random()
        ItDatabase.transaction {
            ItProducts.insert(ItProduct().apply {
                this.id = id; this.price = BigDecimal.fromInt(1); this.qty = 1
                this.displayName = "orig"; this.note = null; this.rank = null
            })
        }
        ItDatabase.transaction {
            ItProducts.upsert(
                ItProduct().apply {
                    this.id = id; this.price = BigDecimal.fromInt(1); this.qty = 1
                    this.displayName = "x"; this.note = null; this.rank = null
                },
                onConflict = ItProducts.id,
                update = ItProduct().apply { this.qty = 99 },
            )
        }
        val row = ItDatabase.autocommit { ItProducts.findOne { where { ItProducts.id eq id } } }
        assertEquals(99, row?.qty)
        assertEquals("orig", row?.displayName) // update only touched qty
        ItDatabase.transaction { ItProducts.deleteWhere(Query(ItProducts.id eq id)) }
    }

    /** insertOrIgnore leaves the existing row untouched on a PK conflict (MySQL no-op upsert). */
    @Test
    fun testInsertOrIgnoreKeepsExisting() {
        assumeDockerAvailable()
        val id = Uuid.random()
        ItDatabase.transaction {
            ItProducts.insert(ItProduct().apply {
                this.id = id; this.price = BigDecimal.fromInt(1); this.qty = 1
                this.displayName = "keep"; this.note = null; this.rank = null
            })
        }
        ItDatabase.transaction {
            ItProducts.insertOrIgnore(
                ItProduct().apply {
                    this.id = id; this.price = BigDecimal.fromInt(2); this.qty = 2
                    this.displayName = "dup"; this.note = null; this.rank = null
                },
                onConflict = ItProducts.id,
            )
        }
        assertEquals("keep", ItDatabase.autocommit { ItProducts.findOne { where { ItProducts.id eq id } } }?.displayName)
        ItDatabase.transaction { ItProducts.deleteWhere(Query(ItProducts.id eq id)) }
    }

    /** A failing statement must not break the driver; the next query still runs. */
    @Test
    fun testConnectionSurvivesQueryError() {
        assumeDockerAvailable()
        ItDatabase.newDriver(poolSize = 1).use { driver ->
            assertFailsWith<Exception> {
                driver.autocommit { execute("SELECT * FROM table_that_does_not_exist", params = emptyMap(), invalidates = emptyList()) { rs -> rs.getInt(0) } }
            }
            assertEquals(1, driver.autocommit { execute("SELECT 1", params = emptyMap(), invalidates = emptyList()) { rs -> rs.getInt(0) } }.single())
        }
    }

    /** Using the driver after close() throws the uniform DatabaseClosedException. */
    @Test
    fun testUseAfterCloseThrows() {
        assumeDockerAvailable()
        val driver = ItDatabase.newDriver(poolSize = 1)
        driver.close()
        assertFailsWith<DatabaseClosedException> {
            driver.autocommit { execute("SELECT 1", params = emptyMap(), invalidates = emptyList()) { rs -> rs.getInt(0) } }
        }
    }

    /** close() is idempotent and isClosed reflects lifecycle state. */
    @Test
    fun testIsClosedAndDoubleClose() {
        assumeDockerAvailable()
        val driver = ItDatabase.newDriver(poolSize = 1)
        assertEquals(false, driver.isClosed)
        driver.close()
        assertEquals(true, driver.isClosed)
        driver.close() // idempotent: must not throw
        assertEquals(true, driver.isClosed)
    }

    /** An exception out of a transaction block must ROLLBACK every statement in it. */
    @Test
    fun testTransactionRollsBackOnException() {
        assumeDockerAvailable()
        val id = Uuid.random()
        assertFailsWith<RuntimeException> {
            ItDatabase.transaction {
                ItProducts.insert(ItProduct().apply {
                    this.id = id; this.price = BigDecimal.fromInt(1); this.qty = 1
                    this.displayName = "rollback"; this.note = null; this.rank = null
                })
                throw RuntimeException("boom")
            }
        }
        assertNull(ItDatabase.autocommit { ItProducts.findOne { where { ItProducts.id eq id } } })
    }

    /** Migrations apply in order, exactly once, and re-running the list is a no-op. */
    @Test
    fun testMigrationsRunOnceAndAreIdempotent() {
        assumeDockerAvailable()
        val suffix = Uuid.random().toString().replace("-", "")
        val table = "mig_probe_$suffix"
        val migrations = listOf(
            Migration<ItCatalog>("001-$suffix", "CREATE TABLE `$table` (id int)"),
            Migration<ItCatalog>("002-$suffix", "INSERT INTO `$table` (id) VALUES (1)"),
        )
        ItDatabase.migrate(migrations)
        ItDatabase.migrate(migrations) // already applied -> no-op

        val rows = ItDatabase.autocommit { execute("SELECT id FROM `$table`", params = emptyMap(), invalidates = emptyList()) { it.getInt(0) } }
        assertEquals(listOf(1), rows)

        ItDatabase.autocommit { executeUpdate("DROP TABLE `$table`", params = emptyMap(), invalidates = emptyList()) }
        ItDatabase.autocommit {
            executeUpdate(
                "DELETE FROM kormium_migrations WHERE id IN (:a, :b)",
                params = mapOf("a" to "001-$suffix", "b" to "002-$suffix"),
                invalidates = emptyList(),
            )
        }
    }

    /** Every supported column type round-trips through a generated table. */
    @Test
    fun testAllColumnTypesRoundTrip() {
        assumeDockerAvailable()
        val id = Uuid.random()
        val instant = kotlin.time.Instant.parse("2024-01-02T03:04:05Z")
        val json = kotlinx.serialization.json.JsonPrimitive("hi")
        val date = kotlinx.datetime.LocalDate.parse("2024-01-02")
        val time = kotlinx.datetime.LocalTime.parse("03:04:05")
        val dateTime = kotlinx.datetime.LocalDateTime.parse("2024-01-02T03:04:05")
        ItDatabase.transaction {
            AllTypes.execSql(allTypesDdl)
            AllTypes.insert(AllTypesEntity().apply {
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
        val row = ItDatabase.autocommit { AllTypes.findOne { where { AllTypes.id eq id } } }!!
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
        ItDatabase.transaction { AllTypes.deleteWhere(Query(AllTypes.id eq id)) }
    }

    /** A duplicate primary key surfaces as a typed UniqueViolationException (vendor 1062). */
    @Test
    fun testUniqueViolationIsTyped() {
        assumeDockerAvailable()
        val id = Uuid.random()
        ItDatabase.transaction {
            ItProducts.insert(ItProduct().apply {
                this.id = id; this.price = BigDecimal.fromInt(1); this.qty = 1
                this.displayName = "dup"; this.note = null; this.rank = null
            })
        }
        assertFailsWith<UniqueViolationException> {
            ItDatabase.transaction {
                ItProducts.insert(ItProduct().apply {
                    this.id = id; this.price = BigDecimal.fromInt(2); this.qty = 2
                    this.displayName = "dup2"; this.note = null; this.rank = null
                })
            }
        }
        ItDatabase.transaction { ItProducts.deleteWhere(Query(ItProducts.id eq id)) }
    }

    /** A foreign-key violation surfaces as a typed ForeignKeyViolationException (vendor 1452). */
    @Test
    fun testForeignKeyViolationIsTyped() {
        assumeDockerAvailable()
        ItDatabase.transaction { Authors.execSql(authorsDdl); Books.execSql(booksDdl) }
        assertFailsWith<ForeignKeyViolationException> {
            ItDatabase.transaction {
                Books.insert(Book().apply {
                    id = Uuid.random(); authorId = Uuid.random(); title = "orphan"
                })
            }
        }
        ItDatabase.transaction {
            Books.execSql("DROP TABLE IF EXISTS `books`")
            Authors.execSql("DROP TABLE IF EXISTS `authors`")
        }
    }

    private fun assumeDockerAvailable() =
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
}

class Author : Entity() {
    var id by Authors.id
    var name by Authors.name
}

object Authors : Table<ItCatalog, Author>("authors", ::Author) {
    val id by Column.UUID()
    val name by Column.Text()

    init { id; name }
}

class Book : Entity() {
    var id by Books.id
    var authorId by Books.authorId
    var title by Books.title
}

object Books : Table<ItCatalog, Book>("books", ::Book) {
    val id by Column.UUID()
    val authorId by Column.UUID()
    val title by Column.Text()

    init { id; authorId; title }
}

class ItProduct : Entity() {
    var id by ItProducts.id
    var price by ItProducts.price
    var qty by ItProducts.qty
    var displayName by ItProducts.displayName
    var note by ItProducts.note
    var rank by ItProducts.rank
}

object ItCatalog : Catalog

class AllTypesEntity : Entity() {
    var id by AllTypes.id
    var anInt by AllTypes.anInt
    var aDouble by AllTypes.aDouble
    var aBool by AllTypes.aBool
    var aText by AllTypes.aText
    var aDecimal by AllTypes.aDecimal
    var anInstant by AllTypes.anInstant
    var aJson by AllTypes.aJson
    var aLong by AllTypes.aLong
    var aFloat by AllTypes.aFloat
    var aShort by AllTypes.aShort
    var aDate by AllTypes.aDate
    var aTime by AllTypes.aTime
    var aDateTime by AllTypes.aDateTime
}

object AllTypes : Table<ItCatalog, AllTypesEntity>("all_types", ::AllTypesEntity) {
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

object ItProducts : Table<ItCatalog, ItProduct>("it_products", ::ItProduct) {
    val id by Column.UUID()
    val price by Column.BigDecimal()
    val qty by Column.Int()
    val displayName by Column.Text()
    val note by Column.Text().nullable()
    val rank by Column.Int().nullable()

    init {
        id; price; qty; displayName; note; rank
    }
}

/**
 * Exposes the JDBC MySQL [createDatabase] driver as a [Database], backed by a MySQL container
 * started once for the test run. The container is launched with mysql_native_password so the
 * first plaintext handshake does not need the caching_sha2_password public-key retrieval dance.
 */
object ItDatabase : Database<ItCatalog> {
    private val container = MySQLContainer("mysql:8.0")
        .withCommand("--default-authentication-plugin=mysql_native_password")
        .apply { start() }

    private val driver = createDatabase(
        host = container.host,
        port = container.firstMappedPort,
        database = container.databaseName,
        user = container.username,
        password = container.password,
    )

    fun newDriver(poolSize: Int) = createDatabase(
        host = container.host,
        port = container.firstMappedPort,
        database = container.databaseName,
        user = container.username,
        password = container.password,
        poolSize = poolSize,
    )

    init {
        driver.autocommit {
            executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS `it_products` (
                    `id` CHAR(36) PRIMARY KEY,
                    `price` DECIMAL(20,4) NOT NULL,
                    `qty` INT NOT NULL,
                    `displayName` VARCHAR(255) NOT NULL,
                    `note` TEXT,
                    `rank` INT
                )
                """.trimIndent(),
                params = emptyMap(),
                invalidates = emptyList(),
            )
        }
    }

    override fun <R> usePinned(
        transactional: Boolean,
        isolation: io.github.kormium.TransactionIsolation?,
        readOnly: Boolean,
        block: (io.github.kormium.SqlExecutor) -> R,
    ): R = driver.usePinned(transactional, isolation, readOnly, block)

    override fun close() = driver.close()
}

// Raw schema DDL for tests (MySQL types). UUID -> CHAR(36); jsonb -> JSON; timestamptz -> TIMESTAMP.
private val allTypesDdl = """CREATE TABLE IF NOT EXISTS `all_types` (`id` CHAR(36) NOT NULL, `anInt` INT NOT NULL, `aDouble` DOUBLE NOT NULL, `aBool` BOOLEAN NOT NULL, `aText` TEXT NOT NULL, `aDecimal` DECIMAL(20,4) NOT NULL, `anInstant` TIMESTAMP NOT NULL, `aJson` JSON NOT NULL, `aLong` BIGINT NOT NULL, `aFloat` FLOAT NOT NULL, `aShort` SMALLINT NOT NULL, `aDate` DATE NOT NULL, `aTime` TIME NOT NULL, `aDateTime` DATETIME NOT NULL, PRIMARY KEY (`id`))"""
private val authorsDdl = """CREATE TABLE IF NOT EXISTS `authors` (`id` CHAR(36) NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY (`id`))"""
private val booksDdl = """CREATE TABLE IF NOT EXISTS `books` (`id` CHAR(36) NOT NULL, `authorId` CHAR(36) NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY (`id`), CONSTRAINT `fk_books_author` FOREIGN KEY (`authorId`) REFERENCES `authors` (`id`))"""
