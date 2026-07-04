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
    fun nonFiniteDecimalWritesReachTheServer() {
        // Non-finite decimals WRITE correctly over r2dbc (Double → float8 → numeric), but
        // r2dbc-postgresql cannot DECODE a numeric NaN/Infinity back (its codec goes through
        // java.math.BigDecimal and throws) — a driver limitation, so this test verifies the
        // stored value through a ::text projection instead of an entity read. The full
        // round-trip is covered on the JDBC path (EdgeCaseTest.nonFiniteDecimalRoundTrips).
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
            val stored = database.suspendAutocommit {
                execute(
                    sql = """SELECT "total"::text AS t FROM "measurements" ORDER BY "total"::text""",
                    params = emptyMap(),
                    invalidates = emptyList(),
                ) { rs -> rs.getString(0) }
            }
            assertEquals(listOf("-Infinity", "Infinity", "NaN"), stored)
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
