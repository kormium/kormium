package io.github.kormium.r2dbc

import io.github.kormium.Catalog
import io.github.kormium.CheckViolationException
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.ForeignKeyViolationException
import io.github.kormium.NotNullViolationException
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.and
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.leftJoin
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Edge-case coverage for the async r2dbc Postgres backend, mirroring the JDBC `EdgeCaseTest` /
 * `QueryCoverageTest` over the suspend API. Exercises the parts that previously had no async
 * coverage: `RETURNING`, composite-conflict upsert/insertOrIgnore, SAVEPOINT rollback, nullable
 * left-join projections, and SQLSTATE → typed-exception mapping (23502/23503/23514). Backend
 * differences are documented in docs/backends.md ("Backend behavior matrix").
 *
 * Skips gracefully when Docker is unavailable.
 */
class R2dbcEdgeCaseTest {

    private val dockerAvailable = DockerClientFactory.instance().isDockerAvailable
    private var container: PostgreSQLContainer<*>? = null
    private var db: SuspendDatabase<EdgeCatalog>? = null

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
            poolSize = 4,
        )
        runBlocking {
            db!!.suspendTransaction {
                EParent.execSql(eParentDdl)
                EChild.execSql(eChildDdl)
                ECheck.execSql(eCheckDdl)
                EUpsert.execSql(eUpsertDdl)
                ENotNull.execSql(eNotNullDdl)
                executeUpdate("""CREATE TABLE IF NOT EXISTS "e_fk_parent" ("id" uuid PRIMARY KEY)""")
                executeUpdate(
                    """CREATE TABLE IF NOT EXISTS "e_fk_child" (""" +
                        """"id" uuid PRIMARY KEY, "parent_id" uuid REFERENCES "e_fk_parent"("id"))"""
                )
            }
        }
    }

    @AfterTest
    fun tearDown() {
        db?.close()
        container?.stop()
    }

    /** insert(returning = true) round-trips the stored row back through RETURNING. */
    @Test
    fun insertReturningHydratesStoredRow() {
        if (!dockerAvailable) return
        runBlocking {
            val id = Uuid.random()
            val stored = db!!.suspendTransaction {
                EParent.insert(EParentRow().apply { this.id = id; name = "async" }, returning = true)
            }
            assertEquals(id, stored?.id)
            assertEquals("async", stored?.name)
        }
    }

    /** Composite-conflict upsert/insertOrIgnore route to DO UPDATE / DO NOTHING on (tenant, sku). */
    @Test
    fun compositeConflictUpsertAndInsertOrIgnore() {
        if (!dockerAvailable) return
        runBlocking {
            val tenant = Uuid.random()
            val sku = "sku-${Uuid.random()}"
            db!!.suspendTransaction {
                EUpsert.upsert(
                    entity = EUpsertRow().apply { this.tenant = tenant; this.sku = sku; qty = 1 },
                    onConflict = listOf(EUpsert.tenant, EUpsert.sku),
                    update = EUpsertRow().apply { qty = 1 },
                )
            }
            db!!.suspendTransaction {
                EUpsert.upsert(
                    entity = EUpsertRow().apply { this.tenant = tenant; this.sku = sku; qty = 99 },
                    onConflict = listOf(EUpsert.tenant, EUpsert.sku),
                    update = EUpsertRow().apply { qty = 5 },
                )
            }
            assertEquals(5, db!!.suspendAutocommit {
                EUpsert.find { where { (EUpsert.tenant eq tenant) and (EUpsert.sku eq sku) } }
            }.single().qty)

            assertEquals(0L, db!!.suspendTransaction {
                EUpsert.insertOrIgnore(
                    EUpsertRow().apply { this.tenant = tenant; this.sku = sku; qty = 7 },
                    onConflict = listOf(EUpsert.tenant, EUpsert.sku),
                )
            })
            assertEquals(1L, db!!.suspendTransaction {
                EUpsert.insertOrIgnore(
                    EUpsertRow().apply { this.tenant = tenant; this.sku = "$sku-2"; qty = 1 },
                    onConflict = listOf(EUpsert.tenant, EUpsert.sku),
                )
            })
        }
    }

    /** A throwing inner SAVEPOINT rolls back only its work; the surrounding row survives. */
    @Test
    fun nestedSavepointRollsBackInnerWork() {
        if (!dockerAvailable) return
        runBlocking {
            val keep = Uuid.random()
            val inner = Uuid.random()
            db!!.suspendTransaction {
                EParent.insert(EParentRow().apply { id = keep; name = "keep" })
                runCatching {
                    savepoint {
                        EParent.insert(EParentRow().apply { id = inner; name = "doomed" })
                        throw RuntimeException("boom")
                    }
                }
            }
            assertEquals(keep, db!!.suspendAutocommit { EParent.findOne { where { EParent.id eq keep } } }?.id)
            assertNull(db!!.suspendAutocommit { EParent.findOne { where { EParent.id eq inner } } })
        }
    }

    /** A left join with no matching right row yields a null right side of the pair. */
    @Test
    fun nullableLeftJoinProjection() {
        if (!dockerAvailable) return
        runBlocking {
            val pid = Uuid.random()
            db!!.suspendTransaction { EParent.insert(EParentRow().apply { id = pid; name = "lonely" }) }
            val pairs: List<Pair<EParentRow, EChildRow?>> = db!!.suspendAutocommit {
                (EParent leftJoin EChild on (EParent.id eq EChild.parentId))
                    .where(EParent.id eq pid)
                    .find()
            }
            assertEquals(1, pairs.size)
            assertEquals(pid, pairs.single().first.id)
            assertNull(pairs.single().second, "an unmatched right side must be null")
        }
    }

    /** NOT NULL violation maps to NotNullViolationException carrying SQLSTATE 23502. */
    @Test
    fun notNullViolationIsTyped() {
        if (!dockerAvailable) return
        runBlocking {
            val ex = assertFailsWith<NotNullViolationException> {
                db!!.suspendTransaction {
                    executeUpdate(
                        """INSERT INTO "e_notnull" ("id") VALUES (:id::uuid)""",
                        mapOf("id" to Uuid.random().toString()),
                    )
                }
            }
            assertEquals("23502", ex.sqlState)
        }
    }

    /** FK violation maps to ForeignKeyViolationException carrying SQLSTATE 23503. */
    @Test
    fun foreignKeyViolationIsTyped() {
        if (!dockerAvailable) return
        runBlocking {
            val ex = assertFailsWith<ForeignKeyViolationException> {
                db!!.suspendTransaction {
                    executeUpdate(
                        """INSERT INTO "e_fk_child" ("id", "parent_id") VALUES (:id::uuid, :p::uuid)""",
                        mapOf("id" to Uuid.random().toString(), "p" to Uuid.random().toString()),
                    )
                }
            }
            assertEquals("23503", ex.sqlState)
        }
    }

    /** CHECK violation maps to CheckViolationException carrying SQLSTATE 23514. */
    @Test
    fun checkViolationIsTyped() {
        if (!dockerAvailable) return
        runBlocking {
            val ex = assertFailsWith<CheckViolationException> {
                db!!.suspendTransaction {
                    ECheck.insert(ECheckRow().apply { id = Uuid.random(); amount = -1 })
                }
            }
            assertEquals("23514", ex.sqlState)
        }
    }
}

object EdgeCatalog : Catalog

class EParentRow : Entity() {
    var id by EParent.id
    var name by EParent.name
}

object EParent : Table<EdgeCatalog, EParentRow>("e_parent", ::EParentRow) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()

    init { id; name }
}

class EChildRow : Entity() {
    var id by EChild.id
    var parentId by EChild.parentId
    var label by EChild.label
}

object EChild : Table<EdgeCatalog, EChildRow>("e_child", ::EChildRow) {
    val id by Column.UUID().primaryKey()
    val parentId by Column.UUID()
    val label by Column.Text()

    init { id; parentId; label }
}

class ECheckRow : Entity() {
    var id by ECheck.id
    var amount by ECheck.amount
}

object ECheck : Table<EdgeCatalog, ECheckRow>("e_check", ::ECheckRow) {
    val id by Column.UUID().primaryKey()
    val amount by Column.Int()

    init { id; amount }
}

class EUpsertRow : Entity() {
    var tenant by EUpsert.tenant
    var sku by EUpsert.sku
    var qty by EUpsert.qty
}

object EUpsert : Table<EdgeCatalog, EUpsertRow>("e_upsert", ::EUpsertRow) {
    val tenant by Column.UUID()
    val sku by Column.Text()
    val qty by Column.Int()

    init { tenant; sku; qty }
}

class ENotNullRow : Entity() {
    var id by ENotNull.id
    var label by ENotNull.label
}

object ENotNull : Table<EdgeCatalog, ENotNullRow>("e_notnull", ::ENotNullRow) {
    val id by Column.UUID().primaryKey()
    val label by Column.Text()

    init { id; label }
}

private val eParentDdl = """CREATE TABLE IF NOT EXISTS "e_parent" ("id" uuid NOT NULL, "name" text NOT NULL, PRIMARY KEY ("id"))"""
private val eChildDdl = """CREATE TABLE IF NOT EXISTS "e_child" ("id" uuid NOT NULL, "parentId" uuid NOT NULL, "label" text NOT NULL, PRIMARY KEY ("id"))"""
private val eCheckDdl = """CREATE TABLE IF NOT EXISTS "e_check" ("id" uuid NOT NULL, "amount" integer NOT NULL CHECK ("amount" >= 0), PRIMARY KEY ("id"))"""
private val eUpsertDdl = """CREATE TABLE IF NOT EXISTS "e_upsert" ("tenant" uuid NOT NULL, "sku" text NOT NULL, "qty" integer NOT NULL, UNIQUE ("tenant", "sku"))"""
private val eNotNullDdl = """CREATE TABLE IF NOT EXISTS "e_notnull" ("id" uuid NOT NULL, "label" text NOT NULL, PRIMARY KEY ("id"))"""
