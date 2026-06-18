import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.github.kormium.Catalog
import io.github.kormium.CheckViolationException
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.ForeignKeyViolationException
import io.github.kormium.Query
import io.github.kormium.SqlParameterSource
import io.github.kormium.UniqueViolationException
import io.github.kormium.and
import io.github.kormium.autocommit
import io.github.kormium.Table
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.eq
import io.github.kormium.resultset.ResultSet
import io.github.kormium.transaction
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.uuid.Uuid
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String): String? = getenv(name)?.toKString()

private fun nativeDriver(poolSize: Int) = createDatabase(
    host = env("KORMIUM_DB_HOST") ?: "localhost",
    port = env("KORMIUM_DB_PORT")?.toInt() ?: 5432,
    database = env("KORMIUM_DB_NAME") ?: "postgres",
    user = env("KORMIUM_DB_USER") ?: "postgres",
    password = env("KORMIUM_DB_PASSWORD") ?: "password",
    poolSize = poolSize,
)

/**
 * End-to-end test for the native (libpq) driver. Testcontainers is JVM-only, so
 * the connection is taken from KORMIUM_DB_* environment variables; the test is
 * skipped when they are not set. Run via the CI workflow (.github/workflows).
 *
 * Exercises the type-binding path that the JVM driver fixed via
 * stringtype=unspecified: a BigDecimal into a numeric column, plus a camelCase
 * column and a nullable int.
 */
class NativeTableIntegrationTest {

    @Test
    fun testBigDecimalAndCamelCaseRoundTrip() {
        if (env("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping native integration test")
            return
        }

        val id = Uuid.random()
        NativeDatabase.transaction {
        NativeProducts.insert(NativeProduct().apply {
            this.id = id
            this.price = BigDecimal.fromInt(100)
            this.qty = 5
            this.displayName = "widget"
            this.note = null
            this.rank = null
        })

        val found = NativeProducts.findById(id)
        assertEquals(id, found?.id)
        assertEquals(5, found?.qty)
        assertEquals("widget", found?.displayName)
        assertNull(found?.note)
        assertNull(found?.rank)
        assertEquals(0, BigDecimal.fromInt(100).compareTo(found?.price!!))

        NativeProducts.deleteWhere(Query(NativeProducts.id eq id))
        assertNull(NativeProducts.findById(id))
        }
    }

    /**
     * Regression: a failed statement must not destroy the connection. With poolSize=1
     * there is a single pooled connection, so if the error path closed it (the old
     * behaviour — PQfinish on any query error) the following valid query would fail.
     */
    @Test
    fun testConnectionSurvivesQueryError() {
        if (env("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping native integration test")
            return
        }
        nativeDriver(poolSize = 1).use { driver ->
            assertFailsWith<Exception> {
                driver.autocommit { execute("SELECT * FROM table_that_does_not_exist") { rs -> rs.getInt(0) } }
            }
            // Same single connection must still be usable after the error above.
            assertEquals(1, driver.autocommit { execute("SELECT 1") { rs -> rs.getInt(0) } }.single())
        }
    }

    /** Using the driver after close() must fail instead of touching a finished connection. */
    @Test
    fun testUseAfterCloseThrows() {
        if (env("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping native integration test")
            return
        }
        val driver = nativeDriver(poolSize = 1)
        driver.close()
        assertFailsWith<Exception> {
            driver.autocommit { execute("SELECT 1") { rs -> rs.getInt(0) } }
        }
    }

    /** An exception out of a transaction block must ROLLBACK every statement in it. */
    @Test
    fun testTransactionRollsBackOnException() {
        if (env("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping native integration test")
            return
        }
        val id = Uuid.random()
        assertFailsWith<RuntimeException> {
            NativeDatabase.transaction {
                NativeProducts.insert(NativeProduct().apply {
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
        assertNull(NativeDatabase.autocommit { NativeProducts.findById(id) })
    }

    /** A duplicate primary key surfaces as a typed UniqueViolationException (SQLSTATE 23505). */
    @Test
    fun testUniqueViolationIsTyped() {
        if (env("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping native integration test")
            return
        }
        val id = Uuid.random()
        NativeDatabase.transaction {
            NativeProducts.insert(NativeProduct().apply {
                this.id = id; this.price = BigDecimal.fromInt(1); this.qty = 1
                this.displayName = "dup"; this.note = null; this.rank = null
            })
        }
        assertFailsWith<UniqueViolationException> {
            NativeDatabase.transaction {
                NativeProducts.insert(NativeProduct().apply {
                    this.id = id; this.price = BigDecimal.fromInt(2); this.qty = 2
                    this.displayName = "dup2"; this.note = null; this.rank = null
                })
            }
        }
        NativeDatabase.transaction { NativeProducts.deleteWhere(Query(NativeProducts.id eq id)) }
    }

    /** A throwing inner SAVEPOINT rolls back only its work; the outer row survives the commit. */
    @Test
    fun testNestedSavepointRollsBackInnerWork() {
        if (env("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping native integration test")
            return
        }
        val keep = Uuid.random()
        val inner = Uuid.random()
        NativeDatabase.transaction {
            NativeProducts.insert(NativeProduct().apply {
                this.id = keep; this.price = BigDecimal.fromInt(1); this.qty = 1
                this.displayName = "keep"; this.note = null; this.rank = null
            })
            runCatching {
                savepoint {
                    NativeProducts.insert(NativeProduct().apply {
                        this.id = inner; this.price = BigDecimal.fromInt(1); this.qty = 2
                        this.displayName = "doomed"; this.note = null; this.rank = null
                    })
                    throw RuntimeException("boom")
                }
            }
        }
        assertEquals(keep, NativeDatabase.autocommit { NativeProducts.findById(keep) }?.id)
        assertNull(NativeDatabase.autocommit { NativeProducts.findById(inner) })
        NativeDatabase.transaction { NativeProducts.deleteWhere(Query(NativeProducts.id eq keep)) }
    }

    /** Composite-conflict upsert/insertOrIgnore route to DO UPDATE / DO NOTHING on (tenant, sku). */
    @Test
    fun testCompositeConflictUpsertAndInsertOrIgnore() {
        if (env("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping native integration test")
            return
        }
        val tenant = Uuid.random()
        val sku = "sku-${Uuid.random()}"
        NativeDatabase.transaction {
            executeUpdate(
                """CREATE TABLE IF NOT EXISTS public.native_upsert (""" +
                    """tenant uuid NOT NULL, sku text NOT NULL, qty int NOT NULL, UNIQUE (tenant, sku))"""
            )
            NativeUpsert.upsert(
                entity = NativeUpsertRow().apply { this.tenant = tenant; this.sku = sku; qty = 1 },
                onConflict = listOf(NativeUpsert.tenant, NativeUpsert.sku),
                update = NativeUpsertRow().apply { qty = 1 },
            )
            NativeUpsert.upsert(
                entity = NativeUpsertRow().apply { this.tenant = tenant; this.sku = sku; qty = 99 },
                onConflict = listOf(NativeUpsert.tenant, NativeUpsert.sku),
                update = NativeUpsertRow().apply { qty = 5 },
            )
        }
        assertEquals(5, NativeDatabase.autocommit {
            NativeUpsert.find(Query((NativeUpsert.tenant eq tenant) and (NativeUpsert.sku eq sku)))
        }.single().qty)
        assertEquals(0L, NativeDatabase.transaction {
            NativeUpsert.insertOrIgnore(
                NativeUpsertRow().apply { this.tenant = tenant; this.sku = sku; qty = 7 },
                onConflict = listOf(NativeUpsert.tenant, NativeUpsert.sku),
            )
        })
        NativeDatabase.transaction { NativeUpsert.deleteWhere(Query(NativeUpsert.tenant eq tenant)) }
    }

    /** CHECK / FK violations surface as the typed exceptions carrying their SQLSTATE. */
    @Test
    fun testCheckAndForeignKeyViolationsAreTyped() {
        if (env("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping native integration test")
            return
        }
        NativeDatabase.transaction {
            executeUpdate(
                """CREATE TABLE IF NOT EXISTS public.native_check (""" +
                    """id uuid PRIMARY KEY, amount int NOT NULL CHECK (amount >= 0))"""
            )
            executeUpdate("""CREATE TABLE IF NOT EXISTS public.native_fk_parent (id uuid PRIMARY KEY)""")
            executeUpdate(
                """CREATE TABLE IF NOT EXISTS public.native_fk_child (""" +
                    """id uuid PRIMARY KEY, parent_id uuid REFERENCES public.native_fk_parent(id))"""
            )
        }
        val check = assertFailsWith<CheckViolationException> {
            NativeDatabase.transaction {
                NativeCheck.insert(NativeCheckRow().apply { id = Uuid.random(); amount = -1 })
            }
        }
        assertEquals("23514", check.sqlState)
        val fk = assertFailsWith<ForeignKeyViolationException> {
            NativeDatabase.transaction {
                executeUpdate(
                    "INSERT INTO public.native_fk_child (id, parent_id) VALUES (:id::uuid, :p::uuid)",
                    mapOf("id" to Uuid.random().toString(), "p" to Uuid.random().toString()),
                )
            }
        }
        assertEquals("23503", fk.sqlState)
    }

    /**
     * End-to-end date/time reads against real Postgres text output. timestamptz is rendered
     * with an hours-only `+00` offset under UTC, which the old fixed-index parser could not
     * handle — getInstant would throw. Reads the four temporal getters from one row.
     */
    @Test
    fun testDateTimeRoundTrip() {
        if (env("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping native integration test")
            return
        }
        nativeDriver(poolSize = 1).use { driver ->
            driver.transaction {
                // Pin the session zone so timestamptz prints the bare "+00" form.
                execute("SET TIME ZONE 'UTC'")
                val rows = execute(
                    """
                    SELECT '2024-01-15 13:45:30.123456+00'::timestamptz,
                           '2024-01-15 13:45:30'::timestamp,
                           '2024-01-15'::date,
                           '13:45:30'::time
                    """.trimIndent()
                ) { rs ->
                    assertEquals(Instant.parse("2024-01-15T13:45:30.123456Z"), rs.getInstant(0))
                    assertEquals(LocalDateTime.parse("2024-01-15T13:45:30"), rs.getLocalDateTime(1))
                    assertEquals(LocalDate.parse("2024-01-15"), rs.getDate(2))
                    assertEquals(LocalTime.parse("13:45:30"), rs.getTime(3))
                }
                assertEquals(1, rows.size)
            }
        }
    }

    /**
     * Stability: 16 workers each run 2000 queries against a pool of 8. Opt-in via the
     * KORM_STABILITY env var so it does not run in the normal CI suite.
     */
    @Test
    fun stabilitySustainedConcurrentLoad() {
        if (env("KORMIUM_DB_HOST") == null || env("KORM_STABILITY") == null) {
            println("KORM_STABILITY not set — skipping native stability test")
            return
        }
        nativeDriver(poolSize = 8).use { driver ->
            val workers = List(16) { Worker.start() }
            val futures = workers.map { worker ->
                worker.execute(TransferMode.SAFE, { driver }) { db ->
                    var ok = 0
                    repeat(2_000) { if (db.autocommit { execute("SELECT 1") { rs -> rs.getInt(0) ?: 0 } }.single() == 1) ok++ }
                    ok
                }
            }
            val total = futures.sumOf { it.result }
            workers.forEach { it.requestTermination().result }
            assertEquals(16 * 2_000, total)
        }
    }

    /**
     * Repeated executions of one statement hit the per-connection parse cache; each call
     * must still bind its own values. poolSize=1 pins every call to the same connection,
     * so from the second iteration on the statement comes from the cache.
     */
    @Test
    fun testRepeatedStatementsBindFreshValues() {
        if (env("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping native integration test")
            return
        }
        nativeDriver(poolSize = 1).use { driver ->
            repeat(10) { i ->
                val got = driver.autocommit {
                    execute("SELECT :a::int + :b::int", mapOf("a" to i, "b" to 100)) { rs -> rs.getInt(0) }
                }.single()
                assertEquals(i + 100, got)
            }
        }
    }

    /** Many worker threads hammering a small pool must not corrupt or crash anything. */
    @Test
    fun testConcurrentQueriesArePoolSafe() {
        if (env("KORMIUM_DB_HOST") == null) {
            println("KORMIUM_DB_HOST not set — skipping native integration test")
            return
        }
        nativeDriver(poolSize = 4).use { driver ->
            val workers = List(8) { Worker.start() }
            val futures = workers.map { worker ->
                worker.execute(TransferMode.SAFE, { driver }) { db ->
                    var sum = 0
                    repeat(50) { sum += db.autocommit { execute("SELECT 1") { rs -> rs.getInt(0) ?: 0 } }.single() }
                    sum
                }
            }
            val total = futures.sumOf { it.result }
            workers.forEach { it.requestTermination().result }
            assertEquals(8 * 50, total)
        }
    }
}

class NativeProduct : Entity() {
    var id by NativeProducts.id
    var price by NativeProducts.price
    var qty by NativeProducts.qty
    var displayName by NativeProducts.displayName
    var note by NativeProducts.note
    var rank by NativeProducts.rank
}

object NativeCatalog : Catalog

class NativeUpsertRow : Entity() {
    var tenant by NativeUpsert.tenant
    var sku by NativeUpsert.sku
    var qty by NativeUpsert.qty
}

object NativeUpsert : Table<NativeCatalog, NativeUpsertRow>("native_upsert", ::NativeUpsertRow) {
    val tenant by Column.UUID()
    val sku by Column.Text()
    val qty by Column.Int()

    init { tenant; sku; qty }
}

class NativeCheckRow : Entity() {
    var id by NativeCheck.id
    var amount by NativeCheck.amount
}

object NativeCheck : Table<NativeCatalog, NativeCheckRow>("native_check", ::NativeCheckRow) {
    val id by Column.UUID().primaryKey()
    val amount by Column.Int()

    init { id; amount }
}

object NativeProducts : Table<NativeCatalog, NativeProduct>("native_products", ::NativeProduct) {
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

object NativeDatabase : Database<NativeCatalog> {
    private val driver = createDatabase(
        host = env("KORMIUM_DB_HOST") ?: "localhost",
        port = env("KORMIUM_DB_PORT")?.toInt() ?: 5432,
        database = env("KORMIUM_DB_NAME") ?: "postgres",
        user = env("KORMIUM_DB_USER") ?: "postgres",
        password = env("KORMIUM_DB_PASSWORD") ?: "password",
    )

    init {
        driver.autocommit {
            executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS public.native_products (
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

    override fun <R> usePinned(transactional: Boolean, block: (io.github.kormium.SqlExecutor) -> R): R =
        driver.usePinned(transactional, block)

    override fun close() = driver.close()
}
