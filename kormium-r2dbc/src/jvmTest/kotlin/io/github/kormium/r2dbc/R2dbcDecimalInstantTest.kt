@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.r2dbc

import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.decimal.Decimal
import io.github.kormium.decimal.decimal
import io.github.kormium.eq
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Round-trips for the two types the Instant/decimal migrations changed, over the async
 * backend: [PostgresR2dbcTypeMapper] binds java.math.BigDecimal as a typed `numeric`
 * parameter and an [Instant] as a UTC `timestamptz`; non-finite decimals travel as float8
 * and assignment-cast to `numeric`. Skips gracefully when Docker isn't available.
 */
class R2dbcDecimalInstantTest {

    private val dockerAvailable = DockerClientFactory.instance().isDockerAvailable
    private var container: PostgreSQLContainer<*>? = null
    private var db: SuspendDatabase<R2Catalog>? = null

    @BeforeTest
    fun setUp() {
        if (!dockerAvailable) return
        val pg = PostgreSQLContainer("postgres:16-alpine")
        pg.start()
        container = pg
        db = createR2dbcDatabase(
            host = pg.host,
            port = pg.firstMappedPort,
            database = pg.databaseName,
            user = pg.username,
            password = pg.password,
            poolSize = 2,
        )
    }

    @AfterTest
    fun tearDown() {
        db?.close()
        container?.stop()
    }

    @Test
    fun decimalRoundTripAndTypedPredicate() {
        if (!dockerAvailable) return
        val database = db!!
        runBlocking {
            val id = Uuid.random()
            database.suspendTransaction {
                Measurements.execSql(measurementsDdl)
                Measurements.insert(Measurement().apply {
                    this.id = id
                    total = Decimal.parse("12345678.90")
                    at = Instant.parse("2026-07-04T12:30:00Z")
                })
            }
            val row = database.suspendAutocommit {
                // The predicate binds a typed numeric parameter — the part StandardTypeMapper
                // used to stringify.
                Measurements.findOne { where { Measurements.total eq Decimal.parse("12345678.90") } }
            }!!
            assertEquals(id, row.id)
            assertEquals(Decimal.parse("12345678.90"), row.total)
            assertEquals(Instant.parse("2026-07-04T12:30:00Z"), row.at)
        }
    }

    @Test
    fun nonFiniteDecimalRoundTrip() {
        // Writes go as Double (float8, assignment-cast to numeric); reads come back through
        // NumericAsTextCodec — the driver's own numeric path would throw on NaN/±Infinity.
        if (!dockerAvailable) return
        val database = db!!
        runBlocking {
            database.suspendTransaction {
                Measurements.execSql(measurementsDdl)
                for (value in listOf(Decimal.NaN, Decimal.POSITIVE_INFINITY, Decimal.NEGATIVE_INFINITY)) {
                    Measurements.insert(Measurement().apply {
                        id = Uuid.random()
                        total = value
                        at = Instant.parse("2026-07-04T00:00:00Z")
                    })
                }
            }
            val values = database.suspendAutocommit {
                Measurements.find { where { Measurements.at eq Instant.parse("2026-07-04T00:00:00Z") } }
            }.map { it.total }
            assertEquals(3, values.size, "expected all three non-finite rows, got $values")
            assertTrue(values.contains(Decimal.NaN), "NaN must round-trip, got $values")
            assertTrue(values.contains(Decimal.POSITIVE_INFINITY), "Infinity must round-trip, got $values")
            assertTrue(values.contains(Decimal.NEGATIVE_INFINITY), "-Infinity must round-trip, got $values")
        }
    }

    @Test
    fun decimalCorpusMatchesServerText() {
        // Oracle check for the binary numeric renderer: for every stored value the entity
        // read (NumericAsTextCodec → Decimal.parse) must equal the server's own ::text.
        if (!dockerAvailable) return
        val database = db!!
        val corpus = listOf(
            "0", "0.00", "42", "-42", "12.34", "-12.34", "12.340000", "0.0001", "-0.0001",
            "0.00000001", "100000000", "10000", "10000.5", "12345678.90",
            "99999999999999999999999999.999999", "1.000000000000000000000000000001",
            "-73786976294838206464.5",
        )
        runBlocking {
            database.suspendTransaction {
                Measurements.execSql(measurementsDdl)
                corpus.forEach { text ->
                    Measurements.insert(Measurement().apply {
                        id = Uuid.random()
                        total = Decimal.parse(text)
                        at = Instant.parse("2026-07-04T01:00:00Z")
                    })
                }
            }
            val byText = database.suspendAutocommit {
                execute(
                    // Fixed literal, no untrusted input: raw params bypass the ColumnType
                    // seam, so an Instant would arrive at the driver unmapped.
                    sql = """SELECT "total"::text AS t FROM "measurements" WHERE "at" = '2026-07-04T01:00:00Z'::timestamptz""",
                    params = emptyMap(),
                    invalidates = emptyList(),
                ) { rs -> rs.getString(0)!! }
            }.sorted()
            val byEntity = database.suspendAutocommit {
                Measurements.find { where { Measurements.at eq Instant.parse("2026-07-04T01:00:00Z") } }
                // toPlainString: server ::text is always plain, while Decimal.toString()
                // switches to scientific notation below 1E-7 (java.math semantics).
            }.map { it.total.toPlainString() }.sorted()
            assertEquals(byText, byEntity)
        }
    }
}

class Measurement : Entity() {
    var id by Measurements.id
    var total by Measurements.total
    var at by Measurements.at
}

object Measurements : Table<R2Catalog, Measurement>("measurements", ::Measurement) {
    val id by Column.UUID().primaryKey()
    val total by Column.decimal()
    val at by Column.Instant()

    init { id; total; at }
}

private val measurementsDdl =
    """CREATE TABLE IF NOT EXISTS "measurements" ("id" uuid NOT NULL, "total" numeric NOT NULL, "at" timestamptz NOT NULL, PRIMARY KEY ("id"))"""
