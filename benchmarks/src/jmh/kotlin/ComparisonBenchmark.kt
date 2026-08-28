@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package kormium.bench

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.eq
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.transaction
import jakarta.persistence.Entity as JpaEntity
import jakarta.persistence.Id
import jakarta.persistence.Table as JpaTable
import org.hibernate.SessionFactory
import org.hibernate.cfg.Configuration
// Exposed 1.x turned its expression builders into top-level functions (SqlExpressionBuilder is
// no longer the `where { }` receiver), so `eq` is imported explicitly — under an alias, because
// this file also uses Kormium's own infix `eq`.
import org.jetbrains.exposed.v1.core.eq as exposedEq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import io.github.kormium.decimal.Decimal as KormiumDecimal
import io.github.kormium.decimal.decimal
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase
import org.jetbrains.exposed.v1.core.Table as ExposedSqlTable
import kotlin.uuid.Uuid as KormiumUuid

// Shared workload shape — keep in sync with the native harness (NativeBenchmark.kt in
// kormium-postgres) so the "Kormium Native" column measures the same thing.
const val BULK_ROWS = 100
const val BATCH_SIZE = 50
const val UPDATE_ROWS = 1024

// --- kormium mapping ---
object Cmp : Catalog

class CmpRow : Entity() {
    var id by CmpTable.id
    var name by CmpTable.name
    var amount by CmpTable.amount
}

object CmpTable : Table<Cmp, CmpRow>("cmp_bench", ::CmpRow) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val amount by Column.decimal()

    init { id; name; amount }
}

// --- Exposed mapping ---
// Exposed 1.x maps `uuid` to kotlin.uuid.Uuid — the same type Kormium uses, so the seeded
// ids are shared with the Kormium benchmarks instead of being converted per call.
object ExposedBench : ExposedSqlTable("cmp_bench") {
    val id = uuid("id")
    val name = text("name")
    val amount = decimal("amount", 20, 2)
    override val primaryKey = PrimaryKey(id)
}

// --- Hibernate mapping ---
@JpaEntity
@JpaTable(name = "cmp_bench")
open class HibBench {
    @Id
    open var id: java.util.UUID = java.util.UUID.randomUUID()
    open var name: String = ""
    open var amount: java.math.BigDecimal = java.math.BigDecimal.ONE
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = ["-Xms2g", "-Xmx2g"])
@Threads(8)
open class ComparisonBenchmark {

    private lateinit var container: PostgreSQLContainer<*>
    private lateinit var kormiumDb: Database<Cmp>
    private lateinit var exposedDb: ExposedDatabase
    private lateinit var exposedDs: HikariDataSource
    private lateinit var sessionFactory: SessionFactory

    private val seededJavaId: java.util.UUID = java.util.UUID.randomUUID()
    private val seededKormiumId: KormiumUuid = seededJavaId.toKormium()

    // Fixed pool of rows the updateById benchmarks target; reseeded every iteration.
    private val updateJavaIds: List<java.util.UUID> = List(UPDATE_ROWS) { java.util.UUID.randomUUID() }
    private val updateKormiumIds: List<KormiumUuid> = updateJavaIds.map { it.toKormium() }

    @Setup
    fun setup() {
        // The jmh fat jar ends up with duplicate META-INF/services/java.sql.Driver entries
        // and the JVM can pick the testcontainers driver over the PostgreSQL one; register
        // the real driver explicitly so DriverManager always finds it.
        Class.forName("org.postgresql.Driver")
        // Use an external Postgres (shared with the native benchmark) when -Dkormium.db.host
        // (forwarded by `-Pbench.db.host`, see build.gradle.kts) or KORMIUM_DB_HOST is set;
        // otherwise spin up an ephemeral Testcontainers one.
        val envHost = dbConfig("host")
        val host: String
        val port: Int
        val database: String
        val user: String
        val password: String
        if (envHost != null) {
            host = envHost
            port = dbConfig("port")?.toInt() ?: 5432
            database = dbConfig("name") ?: "postgres"
            user = dbConfig("user") ?: "postgres"
            password = dbConfig("password") ?: "password"
        } else {
            // tmpfs data dir + durability off: these benchmarks measure ORM/driver overhead,
            // not disk fsync latency, and disk is by far the noisiest component (especially
            // Docker on macOS). All three ORMs get the same treatment.
            container = PostgreSQLContainer("postgres:18-alpine").apply {
                // PostgreSQL 18's image moved the default PGDATA out of
                // /var/lib/postgresql/data; pin it back so the tmpfs mount below actually
                // holds the data directory.
                withEnv("PGDATA", "/var/lib/postgresql/data")
                withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
                withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
                start()
            }
            host = container.host
            port = container.firstMappedPort
            database = container.databaseName
            user = container.username
            password = container.password
        }
        val jdbcUrl = "jdbc:postgresql://$host:$port/$database"

        kormiumDb = createDatabase(host, port, database, user, password, poolSize = 8)
        // Start from a clean table and index `name` so selectWhere/selectMany are index
        // lookups, not size-dependent sequential scans. Seeding happens in resetTable()
        // before every iteration.
        kormiumDb.transaction {
            CmpTable.execSql("DROP TABLE IF EXISTS \"cmp_bench\"")
            CmpTable.execSql(cmpBenchDdl)
            executeUpdate("""CREATE INDEX IF NOT EXISTS cmp_bench_name_idx ON "public"."cmp_bench" ("name")""", params = emptyMap(), invalidates = emptyList())
        }

        exposedDs = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            maximumPoolSize = 8
        })
        exposedDb = ExposedDatabase.connect(exposedDs)

        sessionFactory = Configuration()
            .addAnnotatedClass(HibBench::class.java)
            .setProperty("hibernate.connection.url", jdbcUrl)
            .setProperty("hibernate.connection.username", user)
            .setProperty("hibernate.connection.password", password)
            .setProperty("hibernate.connection.provider_class", "org.hibernate.hikaricp.internal.HikariCPConnectionProvider")
            .setProperty("hibernate.hikari.maximumPoolSize", "8")
            .setProperty("hibernate.hbm2ddl.auto", "none")
            // Lets the persist loop in hibernateBatchInsert actually batch statements,
            // matching kormium insertAll / Exposed batchInsert.
            .setProperty("hibernate.jdbc.batch_size", BATCH_SIZE.toString())
            .buildSessionFactory()
    }

    // The insert benchmarks grow the table and its indexes, which slowly drags every later
    // iteration; truncating between iterations keeps each iteration measuring the same state.
    @Setup(Level.Iteration)
    fun resetTable() {
        kormiumDb.transaction {
            executeUpdate("""TRUNCATE "public"."cmp_bench"""", params = emptyMap(), invalidates = emptyList())
            CmpTable.insert(CmpRow().apply { id = seededKormiumId; name = "seed"; amount = KormiumDecimal.of(1) })
            CmpTable.insertAll(List(BULK_ROWS) { newKormiumRow("bulk") })
            CmpTable.insertAll(updateKormiumIds.map { rowId ->
                CmpRow().apply { id = rowId; name = "upd"; amount = KormiumDecimal.of(1) }
            })
        }
    }

    @TearDown
    fun teardown() {
        runCatching { sessionFactory.close() }
        runCatching { exposedDs.close() }
        runCatching { kormiumDb.close() }
        if (::container.isInitialized) container.stop()
    }

    // --- kormium ---
    @Benchmark
    fun kormiumFindById(): Any? = kormiumDb.autocommit { CmpTable.findOne { where { CmpTable.id eq seededKormiumId } } }

    @Benchmark
    fun kormiumSelectWhere(): Any? = kormiumDb.autocommit { CmpTable.find(Query(CmpTable.name eq "seed")) }

    @Benchmark
    fun kormiumSelectMany(): Any? = kormiumDb.autocommit { CmpTable.find(Query(CmpTable.name eq "bulk")) }

    @Benchmark
    fun kormiumInsert(): Any? = kormiumDb.transaction { CmpTable.insert(newKormiumRow("x")) }

    @Benchmark
    fun kormiumBatchInsert(): Any? = kormiumDb.transaction {
        CmpTable.insertAll(List(BATCH_SIZE) { newKormiumRow("x") })
    }

    @Benchmark
    fun kormiumUpdateById(): Any? = kormiumDb.transaction {
        CmpTable.update(
            CmpRow().apply { amount = KormiumDecimal.of(2) },
            Query(CmpTable.id eq updateKormiumIds[randomUpdateIndex()]),
        )
    }

    // --- Exposed ---
    @Benchmark
    fun exposedFindById(): Any? = transaction(exposedDb) {
        ExposedBench.selectAll().where { ExposedBench.id exposedEq seededKormiumId }.firstOrNull()
    }

    @Benchmark
    fun exposedSelectWhere(): Any? = transaction(exposedDb) {
        ExposedBench.selectAll().where { ExposedBench.name exposedEq "seed" }.toList()
    }

    @Benchmark
    fun exposedSelectMany(): Any? = transaction(exposedDb) {
        ExposedBench.selectAll().where { ExposedBench.name exposedEq "bulk" }.toList()
    }

    @Benchmark
    fun exposedInsert(): Any? = transaction(exposedDb) {
        ExposedBench.insert {
            it[id] = KormiumUuid.random()
            it[name] = "x"
            it[amount] = java.math.BigDecimal.ONE
        }
    }

    @Benchmark
    fun exposedBatchInsert(): Any? = transaction(exposedDb) {
        ExposedBench.batchInsert(List(BATCH_SIZE) { KormiumUuid.random() }, shouldReturnGeneratedValues = false) { rowId ->
            this[ExposedBench.id] = rowId
            this[ExposedBench.name] = "x"
            this[ExposedBench.amount] = java.math.BigDecimal.ONE
        }
    }

    @Benchmark
    fun exposedUpdateById(): Any? = transaction(exposedDb) {
        val rowId = updateKormiumIds[randomUpdateIndex()]
        ExposedBench.update({ ExposedBench.id exposedEq rowId }) { it[amount] = java.math.BigDecimal.TWO }
    }

    // --- Hibernate ---
    @Benchmark
    fun hibernateFindById(): Any? = sessionFactory.openSession().use { it.find(HibBench::class.java, seededJavaId) }

    @Benchmark
    fun hibernateSelectWhere(): Any? = sessionFactory.openSession().use {
        it.createQuery("from HibBench where name = :n", HibBench::class.java).setParameter("n", "seed").list()
    }

    @Benchmark
    fun hibernateSelectMany(): Any? = sessionFactory.openSession().use {
        it.createQuery("from HibBench where name = :n", HibBench::class.java).setParameter("n", "bulk").list()
    }

    @Benchmark
    fun hibernateInsert(): Any? = sessionFactory.openSession().use { s ->
        val tx = s.beginTransaction()
        s.persist(HibBench().apply { id = java.util.UUID.randomUUID(); name = "x"; amount = java.math.BigDecimal.ONE })
        tx.commit()
    }

    @Benchmark
    fun hibernateBatchInsert(): Any? = sessionFactory.openSession().use { s ->
        val tx = s.beginTransaction()
        repeat(BATCH_SIZE) {
            s.persist(HibBench().apply { id = java.util.UUID.randomUUID(); name = "x"; amount = java.math.BigDecimal.ONE })
        }
        tx.commit()
    }

    @Benchmark
    fun hibernateUpdateById(): Any? = sessionFactory.openSession().use { s ->
        val tx = s.beginTransaction()
        val updated = s.createMutationQuery("update HibBench set amount = :a where id = :id")
            .setParameter("a", java.math.BigDecimal.TWO)
            .setParameter("id", updateJavaIds[randomUpdateIndex()])
            .executeUpdate()
        tx.commit()
        updated
    }

    private fun newKormiumRow(rowName: String) =
        CmpRow().apply { id = KormiumUuid.random(); name = rowName; amount = KormiumDecimal.of(1) }

    private fun randomUpdateIndex(): Int = ThreadLocalRandom.current().nextInt(UPDATE_ROWS)

    private fun dbConfig(key: String): String? =
        System.getProperty("kormium.db.$key") ?: System.getenv("KORMIUM_DB_${key.uppercase()}")
}

private fun java.util.UUID.toKormium() = KormiumUuid.fromLongs(mostSignificantBits, leastSignificantBits)

private val cmpBenchDdl = """CREATE TABLE IF NOT EXISTS "cmp_bench" ("id" uuid NOT NULL, "name" text NOT NULL, "amount" numeric NOT NULL, PRIMARY KEY ("id"))"""
