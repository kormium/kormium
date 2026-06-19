import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.DatabaseClosedException
import io.github.kormium.Entity
import io.github.kormium.Query
import io.github.kormium.SqlParameterSource
import io.github.kormium.UniqueViolationException
import io.github.kormium.autocommit
import io.github.kormium.Table
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.migrate.Migration
import io.github.kormium.count
import io.github.kormium.eq
import io.github.kormium.innerJoin
import io.github.kormium.migrate.migrate
import io.github.kormium.query
import io.github.kormium.resultset.ResultSet
import io.github.kormium.transaction
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * End-to-end tests for the JVM driver against a real Postgres (Testcontainers).
 * Unlike the [TableTest] unit tests (which assert generated SQL against a mock),
 * these exercise the whole stack — named-parameter translation in
 * [io.github.kormium.NamedParamStatement], real type binding and
 * [Table.mapToDao] reading rows back.
 *
 * Includes a camelCase column ("displayName") on purpose: it exercises the
 * identifier-quoting fix end-to-end — an unquoted SELECT would fail to find a
 * mixed-case column on a real Postgres.
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

        val found = ItProducts.findById(id)
        assertEquals(id, found?.id)
        assertEquals(5, found?.qty)
        assertEquals("widget", found?.displayName)
        assertNull(found?.note)
        // Regression: a nullable numeric column that is NULL must read back as null, not 0.
        assertNull(found?.rank)
        assertEquals(0, BigDecimal.fromInt(100).compareTo(found?.price!!))

        // Found via a parameterized WHERE on a value.
        val byQty = ItProducts.find(Query(ItProducts.qty eq 5)).filter { it.id == id }
        assertEquals(1, byQty.size)

        // Partial update: only non-null fields go into SET.
        ItProducts.update(Query(ItProducts.id eq id), ItProduct().apply { this.qty = 9 })
        assertEquals(9, ItProducts.findById(id)?.qty)

        ItProducts.deleteWhere(Query(ItProducts.id eq id))
        assertNull(ItProducts.findById(id))
        }
    }

    /**
     * Proves parameterization end-to-end: a value containing a quote (which would
     * break naive string interpolation / enable injection) round-trips intact.
     */
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

        assertEquals(tricky, ItProducts.findById(id)?.displayName)
        }
    }

    /**
     * Regression: [Table.execSql] routes through `execute(sql): Long`, which used
     * to throw (ClassCastException / "no results returned") and could not run DDL.
     */
    @Test
    fun testExecSqlRunsDdl() {
        assumeDockerAvailable()
        ItDatabase.transaction {
            ItProducts.execSql("CREATE TABLE IF NOT EXISTS public.exec_sql_probe (id int)")
            ItProducts.execSql("DROP TABLE public.exec_sql_probe")
        }
    }

    /**
     * Regression: connections must be returned to the Hikari pool. With the leak,
     * more than ~10 calls would exhaust the default pool and block until timeout.
     */
    @Test
    fun testConnectionsAreReleasedAcrossManyCalls() {
        assumeDockerAvailable()
        repeat(30) { i ->
            val id = Uuid.random()
            ItDatabase.transaction {
            ItProducts.insert(ItProduct().apply {
                this.id = id
                this.price = BigDecimal.fromInt(i)
                this.qty = i
                this.displayName = "n$i"
                this.note = null
                this.rank = null
            })
            ItProducts.findById(id)
            }
        }
    }

    /** A failing statement must not break the driver; the next query still runs. */
    @Test
    fun testConnectionSurvivesQueryError() {
        assumeDockerAvailable()
        ItDatabase.newDriver(poolSize = 1).use { driver ->
            assertFailsWith<Exception> {
                driver.autocommit { execute("SELECT * FROM table_that_does_not_exist") { rs -> rs.getInt(0) } }
            }
            assertEquals(1, driver.autocommit { execute("SELECT 1") { rs -> rs.getInt(0) } }.single())
        }
    }

    /** Using the driver after close() throws the uniform DatabaseClosedException. */
    @Test
    fun testUseAfterCloseThrows() {
        assumeDockerAvailable()
        val driver = ItDatabase.newDriver(poolSize = 1)
        driver.close()
        assertFailsWith<DatabaseClosedException> {
            driver.autocommit { execute("SELECT 1") { rs -> rs.getInt(0) } }
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

    /**
     * Regression: the JVM driver's SqlParameterSource overloads used to ignore the
     * source entirely (running the SQL with unbound placeholders). They must now
     * bind each named parameter from the source.
     */
    @Test
    fun testParamSourceBinding() {
        assumeDockerAvailable()
        val source = object : SqlParameterSource {
            private val values = mapOf("a" to 7, "b" to 5)
            override fun hasValue(paramName: String) = paramName in values
            override fun getValue(paramName: String) = values.getValue(paramName)
            override val parameterNames get() = values.keys.toTypedArray()
        }
        ItDatabase.newDriver(poolSize = 1).use { driver ->
            // The SqlParameterSource overload lives on the pinned SqlExecutor (Scope exposes only
            // the Map form), so exercise it through usePinned.
            val sum = driver.usePinned(transactional = false) { exec ->
                exec.execute("SELECT :a::int + :b::int AS s", source) { rs -> rs.getInt(0) }
            }
            assertEquals(12, sum.single())
        }
    }

    /** An exception out of a transaction block must ROLLBACK every statement in it. */
    @Test
    fun testTransactionRollsBackOnException() {
        assumeDockerAvailable()
        val id = Uuid.random()
        assertFailsWith<RuntimeException> {
            ItDatabase.transaction {
                ItProducts.insert(ItProduct().apply {
                    this.id = id
                    this.price = BigDecimal.fromInt(1)
                    this.qty = 1
                    this.displayName = "rollback"
                    this.note = null
                    this.rank = null
                })
                throw RuntimeException("boom")
            }
        }
        // The insert above must not have been committed.
        assertNull(ItDatabase.autocommit { ItProducts.findById(id) })
    }

    /**
     * A failing [io.github.kormium.Scope.savepoint] rolls back only its own work
     * (ROLLBACK TO SAVEPOINT); the enclosing transaction keeps the rest and commits.
     */
    @Test
    fun testSavepointPartialRollback() {
        assumeDockerAvailable()
        val kept = Uuid.random()
        val rolled = Uuid.random()
        ItDatabase.transaction {
            ItProducts.insert(ItProduct().apply {
                this.id = kept
                this.price = BigDecimal.fromInt(1)
                this.qty = 1
                this.displayName = "kept"
                this.note = null
                this.rank = null
            })
            runCatching {
                savepoint {
                    ItProducts.insert(ItProduct().apply {
                        this.id = rolled
                        this.price = BigDecimal.fromInt(2)
                        this.qty = 2
                        this.displayName = "rolled"
                        this.note = null
                        this.rank = null
                    })
                    throw RuntimeException("nope")
                }
            }
        }
        assertEquals("kept", ItDatabase.autocommit { ItProducts.findById(kept) }?.displayName)
        assertNull(ItDatabase.autocommit { ItProducts.findById(rolled) })
    }

    /** new() returns the stored row via RETURNING. */
    @Test
    fun testNewReturnsInsertedRow() {
        assumeDockerAvailable()
        val id = Uuid.random()
        val returned = ItDatabase.transaction {
            ItProducts.insert(
                ItProduct().apply {
                    this.id = id
                    this.price = BigDecimal.fromInt(7)
                    this.qty = 7
                    this.displayName = "ret"
                    this.note = null
                    this.rank = null
                },
                returning = true,
            )
        }
        assertEquals(id, returned?.id)
        assertEquals("ret", returned?.displayName)
        ItDatabase.transaction { ItProducts.deleteWhere(Query(ItProducts.id eq id)) }
    }

    /** Batch insert stores every row in one statement; count() matches a predicate. */
    @Test
    fun testBatchInsertAndCount() {
        assumeDockerAvailable()
        val ids = List(3) { Uuid.random() }
        val inserted = ItDatabase.transaction {
            ItProducts.insertAll(ids.mapIndexed { i, id ->
                ItProduct().apply {
                    this.id = id
                    this.price = BigDecimal.fromInt(i)
                    this.qty = i
                    this.displayName = "batch$i"
                    this.note = null
                    this.rank = null
                }
            })
        }
        assertEquals(3, inserted.size)
        val count = ItDatabase.autocommit { ItProducts.count(Query(ItProducts.displayName eq "batch1")) }
        assertEquals(1L, count)
        ItDatabase.transaction { ids.forEach { ItProducts.deleteWhere(Query(ItProducts.id eq it)) } }
    }

    /** Migrations apply in order, exactly once, and re-running the list is a no-op. */
    @Test
    fun testMigrationsRunOnceAndAreIdempotent() {
        assumeDockerAvailable()
        val suffix = Uuid.random().toString().replace("-", "")
        val table = "mig_probe_$suffix"
        val migrations = listOf(
            Migration<ItCatalog>("001-$suffix", "CREATE TABLE public.$table (id int)"),
            Migration<ItCatalog>("002-$suffix", "INSERT INTO public.$table (id) VALUES (1)"),
        )
        ItDatabase.migrate(migrations)
        ItDatabase.migrate(migrations) // already applied → no-op

        // The row inserted by 002 exists exactly once, proving each migration ran a single time.
        val rows = ItDatabase.autocommit { execute("SELECT id FROM public.$table") { it.getInt(0) } }
        assertEquals(listOf(1), rows)

        ItDatabase.autocommit { executeUpdate("DROP TABLE public.$table") }
        ItDatabase.autocommit {
            executeUpdate(
                "DELETE FROM kormium_migrations WHERE id IN (:a, :b)",
                mapOf("a" to "001-$suffix", "b" to "002-$suffix"),
            )
        }
    }

    /** Every supported column type round-trips through a generated table. */
    @Test
    fun testAllColumnTypesRoundTrip() {
        assumeDockerAvailable()
        val id = Uuid.random()
        val instant = kotlinx.datetime.Instant.parse("2024-01-02T03:04:05Z")
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
        val row = ItDatabase.autocommit { AllTypes.findById(id) }!!
        assertEquals(42, row.anInt)
        assertEquals(2.5, row.aDouble)
        assertEquals(true, row.aBool)
        assertEquals("txt", row.aText)
        assertEquals(0, BigDecimal.fromInt(123).compareTo(row.aDecimal!!))
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

    /** Many threads hammering a small pool must not corrupt results or block forever. */
    @Test
    fun testConcurrentQueriesArePoolSafe() {
        assumeDockerAvailable()
        val sum = java.util.concurrent.atomic.AtomicInteger(0)
        ItDatabase.newDriver(poolSize = 4).use { driver ->
            val threads = (1..8).map {
                Thread {
                    repeat(50) { sum.addAndGet(driver.autocommit { execute("SELECT 1") { rs -> rs.getInt(0) ?: 0 } }.single()) }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }
        assertEquals(8 * 50, sum.get())
    }

    /** A join round-trips three ways: ResultRow (A), projection+mapper (C), entity pairs (B). */
    @Test
    fun testJoinRoundTrip() {
        assumeDockerAvailable()
        val authorId = Uuid.random()
        val bookId = Uuid.random()
        ItDatabase.transaction {
            Authors.execSql(authorsDdl)
            Books.execSql(booksDdl)
            Authors.insert(Author().apply { id = authorId; name = "Ada" })
            Books.insert(Book().apply { id = bookId; this.authorId = authorId; title = "Notes" })
        }

        // A — ResultRow
        val rows = ItDatabase.autocommit {
            (Authors innerJoin Books on (Authors.id eq Books.authorId)).select()
        }
        assertEquals(1, rows.size)
        assertEquals("Ada", rows.single()[Authors.name])
        assertEquals("Notes", rows.single()[Books.title])

        // C — projection into a tuple
        val titles = ItDatabase.autocommit {
            (Authors innerJoin Books on (Authors.id eq Books.authorId))
                .select(Authors.name, Books.title) { it[Authors.name] to it[Books.title] }
        }
        assertEquals(listOf("Ada" to "Notes"), titles)

        // B — entity pairs
        val pairs = ItDatabase.autocommit {
            (Authors innerJoin Books on (Authors.id eq Books.authorId)).find()
        }
        assertEquals(1, pairs.size)
        assertEquals("Ada", pairs.single().first.name)
        assertEquals(bookId, pairs.single().second.id)

        ItDatabase.transaction { Books.execSql("DROP TABLE IF EXISTS \"books\""); Authors.execSql("DROP TABLE IF EXISTS \"authors\"") }
    }

    /** GROUP BY with COUNT(*) aggregates rows per group. */
    @Test
    fun testAggregationGroupBy() {
        assumeDockerAvailable()
        val ids = List(3) { Uuid.random() }
        ItDatabase.transaction {
            ItProducts.insertAll(ids.mapIndexed { i, id ->
                ItProduct().apply {
                    this.id = id
                    this.price = BigDecimal.fromInt(10)
                    this.qty = if (i == 0) 5 else 7
                    this.displayName = "agg"
                    this.note = null
                    this.rank = null
                }
            })
        }
        val cnt = count()
        val byQty = ItDatabase.autocommit {
            ItProducts.query()
                .where(ItProducts.displayName eq "agg")
                .groupBy(ItProducts.qty)
                .select(ItProducts.qty, cnt)
        }.associate { it[ItProducts.qty] to it[cnt] }
        assertEquals(1L, byQty[5])
        assertEquals(2L, byQty[7])
        ItDatabase.transaction { ids.forEach { ItProducts.deleteWhere(Query(ItProducts.id eq it)) } }
    }

    /** A duplicate primary key surfaces as a typed UniqueViolationException (SQLSTATE 23505). */
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

    // Skip (rather than fail) these tests where Docker is unavailable, e.g. CI runners
    // without a Docker daemon. Must run before any reference to the Postgres-backed table.
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
        id;price;qty;displayName;note;rank
    }
}

/**
 * Adapter that exposes the Hikari/JDBC [createDatabase] driver as a [Database],
 * backed by a Postgres container started once for the test run.
 */
object ItDatabase : Database<ItCatalog> {
    private val container = PostgreSQLContainer("postgres:16-alpine").apply { start() }

    private val driver = createDatabase(
        host = container.host,
        port = container.firstMappedPort,
        database = container.databaseName,
        user = container.username,
        password = container.password,
    )

    /** A separate driver against the same container, for tests that need their own pool/lifecycle. */
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
                CREATE TABLE IF NOT EXISTS public.it_products (
                    id uuid PRIMARY KEY,
                    price numeric NOT NULL,
                    qty int NOT NULL,
                    "displayName" text NOT NULL,
                    note text,
                    rank int
                )
                """.trimIndent()
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

// Raw schema DDL for tests (Kormium no longer owns createTable). Postgres types.
private val allTypesDdl = """CREATE TABLE IF NOT EXISTS "all_types" ("id" uuid NOT NULL, "anInt" integer NOT NULL, "aDouble" double precision NOT NULL, "aBool" boolean NOT NULL, "aText" text NOT NULL, "aDecimal" numeric NOT NULL, "anInstant" timestamptz NOT NULL, "aJson" jsonb NOT NULL, "aLong" bigint NOT NULL, "aFloat" real NOT NULL, "aShort" smallint NOT NULL, "aDate" date NOT NULL, "aTime" time NOT NULL, "aDateTime" timestamp NOT NULL, PRIMARY KEY ("id"))"""
private val authorsDdl = """CREATE TABLE IF NOT EXISTS "authors" ("id" uuid NOT NULL, "name" text NOT NULL, PRIMARY KEY ("id"))"""
private val booksDdl = """CREATE TABLE IF NOT EXISTS "books" ("id" uuid NOT NULL, "authorId" uuid NOT NULL, "title" text NOT NULL, PRIMARY KEY ("id"))"""
