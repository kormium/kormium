import io.github.kormium.Catalog
import io.github.kormium.CheckViolationException
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.ForeignKeyViolationException
import io.github.kormium.NotNullViolationException
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.UniqueViolationException
import io.github.kormium.Value
import io.github.kormium.and
import io.github.kormium.autocommit
import io.github.kormium.avg
import io.github.kormium.count
import io.github.kormium.eq
import io.github.kormium.gtEq
import io.github.kormium.innerJoin
import io.github.kormium.max
import io.github.kormium.min
import io.github.kormium.or
import io.github.kormium.query
import io.github.kormium.sum
import io.github.kormium.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Query-coverage end-to-end tests against real Postgres (Testcontainers), reusing
 * [ItDatabase] / [ItCatalog] from [TableIntegrationTest]. Before-1.0 roadmap:
 * "Query Coverage + Backend Reliability". Three directions:
 *
 *  1. JOINs where both tables have a column of the *same* name — the qualified
 *     SELECT (`"table"."col"`) must resolve each side to the right column.
 *  2. Aggregations (count/sum/avg/min/max) and HAVING filtering after GROUP BY.
 *  3. SQLSTATE mapping (23505 / 23502 / 23503) to typed Kormium exceptions.
 *
 * Purely additive; skipped (not failed) when Docker is unavailable.
 */
class QueryCoverageTest {

    @BeforeTest
    fun setup() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
        ItDatabase.transaction {
            QcDepts.execSql(qcDeptsDdl)
            QcEmps.execSql(qcEmpsDdl)
            QcSales.execSql(qcSalesDdl)
            QcUnique.execSql(qcUniqueDdl)
            QcNotNull.execSql(qcNotNullDdl)
            QcCheck.execSql(qcCheckDdl)
            QcUpsert.execSql(qcUpsertDdl)
            executeUpdate("CREATE TABLE IF NOT EXISTS qc_fk_parent (id uuid PRIMARY KEY)")
            executeUpdate(
                "CREATE TABLE IF NOT EXISTS qc_fk_child (" +
                    "id uuid PRIMARY KEY, parent_id uuid REFERENCES qc_fk_parent(id))"
            )
        }
    }

    // ---- 1. JOIN with colliding column names ----------------------------------------------

    @Test
    fun joinWithCollidingColumnNamesResolvesToCorrectTable() {
        val deptId = Uuid.random()
        val empId = Uuid.random()
        ItDatabase.transaction {
            QcDepts.insert(QcDept().apply { id = deptId; name = "Engineering" })
            QcEmps.insert(QcEmp().apply { id = empId; this.deptId = deptId; name = "Ada" })
        }
        val rows = ItDatabase.autocommit {
            (QcDepts innerJoin QcEmps on (QcDepts.id eq QcEmps.deptId)).select()
        }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("Engineering", row[QcDepts.name])
        assertEquals("Ada", row[QcEmps.name])
        assertEquals(deptId, row[QcDepts.id])
        assertEquals(empId, row[QcEmps.id])

        val pairs = ItDatabase.autocommit {
            (QcDepts innerJoin QcEmps on (QcDepts.id eq QcEmps.deptId)).find()
        }
        assertEquals(1, pairs.size)
        assertEquals("Engineering", pairs.single().first.name)
        assertEquals("Ada", pairs.single().second.name)
        assertEquals(deptId, pairs.single().first.id)
        assertEquals(empId, pairs.single().second.id)

        ItDatabase.transaction {
            QcEmps.deleteWhere(Query(QcEmps.id eq empId))
            QcDepts.deleteWhere(Query(QcDepts.id eq deptId))
        }
    }

    @Test
    fun projectionOfBothCollidingColumns() {
        val deptId = Uuid.random()
        ItDatabase.transaction {
            QcDepts.insert(QcDept().apply { id = deptId; name = "Sales" })
            QcEmps.insert(QcEmp().apply { id = Uuid.random(); this.deptId = deptId; name = "Grace" })
        }
        val pair = ItDatabase.autocommit {
            (QcDepts innerJoin QcEmps on (QcDepts.id eq QcEmps.deptId))
                .select(QcDepts.name, QcEmps.name) { it[QcDepts.name] to it[QcEmps.name] }
        }
        assertEquals(listOf("Sales" to "Grace"), pair)
        ItDatabase.transaction {
            QcEmps.deleteWhere { where { QcEmps.deptId eq deptId } }
            QcDepts.deleteWhere(Query(QcDepts.id eq deptId))
        }
    }

    // ---- 2. Aggregations + HAVING ----------------------------------------------------------

    @Test
    fun aggregateFunctionsOverAGroup() {
        val tag = "agg-${Uuid.random()}"
        ItDatabase.transaction {
            QcSales.insertAll(listOf(10, 20, 30).map { amt ->
                QcSale().apply { id = Uuid.random(); region = tag; amount = amt }
            })
        }
        val cnt = count()
        val total = QcSales.amount.sum()
        val mean = QcSales.amount.avg()
        val lo = QcSales.amount.min()
        val hi = QcSales.amount.max()
        val row = ItDatabase.autocommit {
            QcSales.query().where(QcSales.region eq tag).select(cnt, total, mean, lo, hi)
        }.single()
        assertEquals(3L, row[cnt])
        assertEquals(60L, row[total])
        assertEquals(20.0, row[mean])
        assertEquals(10, row[lo])
        assertEquals(30, row[hi])
        ItDatabase.transaction { QcSales.deleteWhere(Query(QcSales.region eq tag)) }
    }

    @Test
    fun havingFiltersGroupsAfterAggregation() {
        val many = "hm-${Uuid.random()}"
        val few = "hf-${Uuid.random()}"
        ItDatabase.transaction {
            QcSales.insertAll(
                listOf(
                    QcSale().apply { id = Uuid.random(); region = many; amount = 1 },
                    QcSale().apply { id = Uuid.random(); region = many; amount = 2 },
                    QcSale().apply { id = Uuid.random(); region = many; amount = 3 },
                    QcSale().apply { id = Uuid.random(); region = few; amount = 9 },
                )
            )
        }
        val cnt = count()
        val survivors = ItDatabase.autocommit {
            QcSales.query()
                .where((QcSales.region eq many) or (QcSales.region eq few))
                .groupBy(QcSales.region)
                .having(cnt gtEq Value(2))
                .select(QcSales.region, cnt)
        }.associate { it[QcSales.region] to it[cnt] }
        assertEquals(mapOf(many to 3L), survivors)
        assertTrue(few !in survivors)
        ItDatabase.transaction {
            QcSales.deleteWhere(Query(QcSales.region eq many))
            QcSales.deleteWhere(Query(QcSales.region eq few))
        }
    }

    @Test
    fun havingOnSumAggregate() {
        val rich = "sr-${Uuid.random()}"
        val poor = "sp-${Uuid.random()}"
        ItDatabase.transaction {
            QcSales.insertAll(
                listOf(
                    QcSale().apply { id = Uuid.random(); region = rich; amount = 60 },
                    QcSale().apply { id = Uuid.random(); region = rich; amount = 40 },
                    QcSale().apply { id = Uuid.random(); region = poor; amount = 5 },
                )
            )
        }
        val total = QcSales.amount.sum()
        val survivors = ItDatabase.autocommit {
            QcSales.query()
                .where((QcSales.region eq rich) or (QcSales.region eq poor))
                .groupBy(QcSales.region)
                .having(total gtEq Value(50))
                .select(QcSales.region, total)
        }.associate { it[QcSales.region] to it[total] }
        assertEquals(mapOf(rich to 100L), survivors)
        ItDatabase.transaction {
            QcSales.deleteWhere(Query(QcSales.region eq rich))
            QcSales.deleteWhere(Query(QcSales.region eq poor))
        }
    }

    // ---- 3. SQLSTATE mapping ---------------------------------------------------------------

    /** UNIQUE (non-PK) violation maps to UniqueViolationException carrying SQLSTATE 23505. */
    @Test
    fun uniqueConstraintViolationIsTyped() {
        val email = "u-${Uuid.random()}@b.c"
        ItDatabase.transaction { QcUnique.insert(QcUniqueRow().apply { id = Uuid.random(); this.email = email }) }
        val ex = assertFailsWith<UniqueViolationException> {
            ItDatabase.transaction { QcUnique.insert(QcUniqueRow().apply { id = Uuid.random(); this.email = email }) }
        }
        assertEquals("23505", ex.sqlState)
        ItDatabase.transaction { QcUnique.deleteWhere(Query(QcUnique.email eq email)) }
    }

    /** NOT NULL violation maps to NotNullViolationException carrying SQLSTATE 23502. */
    @Test
    fun notNullConstraintViolationIsTyped() {
        val ex = assertFailsWith<NotNullViolationException> {
            ItDatabase.transaction {
                // Bind the uuid as text + cast: raw executeUpdate does not run the
                // typed column mapper, and pgjdbc cannot infer a SQL type for kotlin.uuid.Uuid.
                executeUpdate(
                    "INSERT INTO qc_notnull (id) VALUES (:id::uuid)",
                    mapOf("id" to Uuid.random().toString()),
                )
            }
        }
        assertEquals("23502", ex.sqlState)
    }

    /** FK violation maps to ForeignKeyViolationException carrying SQLSTATE 23503. */
    @Test
    fun foreignKeyViolationIsTyped() {
        val ex = assertFailsWith<ForeignKeyViolationException> {
            ItDatabase.transaction {
                executeUpdate(
                    "INSERT INTO qc_fk_child (id, parent_id) VALUES (:id::uuid, :p::uuid)",
                    mapOf("id" to Uuid.random().toString(), "p" to Uuid.random().toString()),
                )
            }
        }
        assertEquals("23503", ex.sqlState)
    }

    /** CHECK constraint violation maps to CheckViolationException carrying SQLSTATE 23514. */
    @Test
    fun checkConstraintViolationIsTyped() {
        val ex = assertFailsWith<CheckViolationException> {
            ItDatabase.transaction {
                QcCheck.insert(QcCheckRow().apply { id = Uuid.random(); amount = -1 })
            }
        }
        assertEquals("23514", ex.sqlState)
    }

    // ---- 4. Composite-conflict upsert / insertOrIgnore -------------------------------------

    /**
     * `upsert` / `insertOrIgnore` with a *composite* conflict target ((tenant, sku)): the first
     * write inserts, the second collides on both columns and is routed to DO UPDATE / DO NOTHING.
     */
    @Test
    fun compositeConflictUpsertAndInsertOrIgnore() {
        val tenant = Uuid.random()
        val sku = "sku-${Uuid.random()}"
        ItDatabase.transaction {
            QcUpsert.upsert(
                entity = QcUpsertRow().apply { this.tenant = tenant; this.sku = sku; qty = 1 },
                onConflict = listOf(QcUpsert.tenant, QcUpsert.sku),
                update = QcUpsertRow().apply { qty = 1 },
            )
        }
        // Conflict on the full (tenant, sku) pair → DO UPDATE applies the patch.
        ItDatabase.transaction {
            QcUpsert.upsert(
                entity = QcUpsertRow().apply { this.tenant = tenant; this.sku = sku; qty = 99 },
                onConflict = listOf(QcUpsert.tenant, QcUpsert.sku),
                update = QcUpsertRow().apply { qty = 5 },
            )
        }
        assertEquals(5, ItDatabase.autocommit {
            QcUpsert.find { where { (QcUpsert.tenant eq tenant) and (QcUpsert.sku eq sku) } }
        }.single().qty)

        // insertOrIgnore: same composite key → 0 rows; a fresh sku → 1 row.
        assertEquals(0L, ItDatabase.transaction {
            QcUpsert.insertOrIgnore(
                QcUpsertRow().apply { this.tenant = tenant; this.sku = sku; qty = 123 },
                onConflict = listOf(QcUpsert.tenant, QcUpsert.sku),
            )
        })
        assertEquals(1L, ItDatabase.transaction {
            QcUpsert.insertOrIgnore(
                QcUpsertRow().apply { this.tenant = tenant; this.sku = "$sku-2"; qty = 1 },
                onConflict = listOf(QcUpsert.tenant, QcUpsert.sku),
            )
        })
        ItDatabase.transaction { QcUpsert.deleteWhere(Query(QcUpsert.tenant eq tenant)) }
    }

    // ---- 5. Block DSL equivalence with an explicit Query ----------------------------------

    /** `find { where { ... } }` must return exactly what the explicit `find(Query(...))` does. */
    @Test
    fun blockDslEqualsExplicitQuery() {
        val tag = "eq-${Uuid.random()}"
        ItDatabase.transaction {
            QcSales.insertAll(listOf(7, 8).map { amt ->
                QcSale().apply { id = Uuid.random(); region = tag; amount = amt }
            })
        }
        val viaQuery = ItDatabase.autocommit { QcSales.find(Query(QcSales.region eq tag)) }
            .map { it.amount }.sorted()
        val viaBlock = ItDatabase.autocommit { QcSales.find { where { QcSales.region eq tag } } }
            .map { it.amount }.sorted()
        assertEquals(viaQuery, viaBlock)
        assertEquals(listOf(7, 8), viaBlock)
        assertNull(ItDatabase.autocommit { QcSales.find { where { QcSales.region eq "nope-$tag" } } }.firstOrNull())
        ItDatabase.transaction { QcSales.deleteWhere(Query(QcSales.region eq tag)) }
    }
}

// dept and emp deliberately share the column names "id" and "name".
class QcDept : Entity() {
    var id by QcDepts.id
    var name by QcDepts.name
}

object QcDepts : Table<ItCatalog, QcDept>("qc_depts", ::QcDept) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()

    init { id; name }
}

class QcEmp : Entity() {
    var id by QcEmps.id
    var deptId by QcEmps.deptId
    var name by QcEmps.name
}

object QcEmps : Table<ItCatalog, QcEmp>("qc_emps", ::QcEmp) {
    val id by Column.UUID().primaryKey()
    val deptId by Column.UUID()
    val name by Column.Text()

    init { id; deptId; name }
}

class QcSale : Entity() {
    var id by QcSales.id
    var region by QcSales.region
    var amount by QcSales.amount
}

object QcSales : Table<ItCatalog, QcSale>("qc_sales", ::QcSale) {
    val id by Column.UUID().primaryKey()
    val region by Column.Text()
    val amount by Column.Int()

    init { id; region; amount }
}

class QcUniqueRow : Entity() {
    var id by QcUnique.id
    var email by QcUnique.email
}

object QcUnique : Table<ItCatalog, QcUniqueRow>("qc_unique", ::QcUniqueRow) {
    val id by Column.UUID().primaryKey()
    val email by Column.Text()

    init { id; email }
}

class QcNotNullRow : Entity() {
    var id by QcNotNull.id
    var label by QcNotNull.label
}

object QcNotNull : Table<ItCatalog, QcNotNullRow>("qc_notnull", ::QcNotNullRow) {
    val id by Column.UUID().primaryKey()
    val label by Column.Text()

    init { id; label }
}

class QcCheckRow : Entity() {
    var id by QcCheck.id
    var amount by QcCheck.amount
}

object QcCheck : Table<ItCatalog, QcCheckRow>("qc_check", ::QcCheckRow) {
    val id by Column.UUID().primaryKey()
    val amount by Column.Int()

    init { id; amount }
}

class QcUpsertRow : Entity() {
    var tenant by QcUpsert.tenant
    var sku by QcUpsert.sku
    var qty by QcUpsert.qty
}

object QcUpsert : Table<ItCatalog, QcUpsertRow>("qc_upsert", ::QcUpsertRow) {
    val tenant by Column.UUID()
    val sku by Column.Text()
    val qty by Column.Int()

    init { tenant; sku; qty }
}

// Raw schema DDL (Kormium does not own schema management). Postgres types.
private val qcDeptsDdl = """CREATE TABLE IF NOT EXISTS "qc_depts" ("id" uuid NOT NULL, "name" text NOT NULL, PRIMARY KEY ("id"))"""
private val qcEmpsDdl = """CREATE TABLE IF NOT EXISTS "qc_emps" ("id" uuid NOT NULL, "deptId" uuid NOT NULL, "name" text NOT NULL, PRIMARY KEY ("id"))"""
private val qcSalesDdl = """CREATE TABLE IF NOT EXISTS "qc_sales" ("id" uuid NOT NULL, "region" text NOT NULL, "amount" integer NOT NULL, PRIMARY KEY ("id"))"""
private val qcUniqueDdl = """CREATE TABLE IF NOT EXISTS "qc_unique" ("id" uuid NOT NULL, "email" text NOT NULL UNIQUE, PRIMARY KEY ("id"))"""
private val qcNotNullDdl = """CREATE TABLE IF NOT EXISTS "qc_notnull" ("id" uuid NOT NULL, "label" text NOT NULL, PRIMARY KEY ("id"))"""
private val qcCheckDdl = """CREATE TABLE IF NOT EXISTS "qc_check" ("id" uuid NOT NULL, "amount" integer NOT NULL CHECK ("amount" >= 0), PRIMARY KEY ("id"))"""
private val qcUpsertDdl = """CREATE TABLE IF NOT EXISTS "qc_upsert" ("tenant" uuid NOT NULL, "sku" text NOT NULL, "qty" integer NOT NULL, UNIQUE ("tenant", "sku"))"""
