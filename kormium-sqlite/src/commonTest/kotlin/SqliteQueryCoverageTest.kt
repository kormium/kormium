@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

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
import io.github.kormium.autocommit
import io.github.kormium.avg
import io.github.kormium.count
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.eq
import io.github.kormium.gtEq
import io.github.kormium.innerJoin
import io.github.kormium.max
import io.github.kormium.min
import io.github.kormium.or
import io.github.kormium.query
import io.github.kormium.sum
import io.github.kormium.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Extra query-coverage tests for the SQLite backend (Before-1.0 roadmap:
 * "Query Coverage + Backend Reliability"). They run on every target against a
 * shared in-memory database, so no external server / Docker is needed.
 *
 * Three directions:
 *  1. JOINs where both tables have a column of the *same* name — the result must
 *     resolve each side to the right table's column.
 *  2. Aggregations (count/sum/avg/min/max) and HAVING filtering after GROUP BY.
 *  3. SQLSTATE / constraint-violation mapping to typed Kormium exceptions.
 *
 * These are purely additive; they reuse the public DSL only.
 */
class SqliteQueryCoverageTest {

    private val db: Database<QcCatalog> = createSqliteDatabase(":memory:")

    // ---- 1. JOIN with colliding column names ----------------------------------------------

    /**
     * Both [QcDepts] and [QcEmps] declare columns "id" and "name". The qualified SELECT must
     * keep them distinct so `row[QcDepts.name]` and `row[QcEmps.name]` read the right side.
     */
    @Test
    fun joinWithCollidingColumnNamesResolvesToCorrectTable() {
        val deptId = Uuid.random()
        val empId = Uuid.random()
        db.transaction {
            QcDepts.execSql(qcDeptsDdl)
            QcEmps.execSql(qcEmpsDdl)
            QcDepts.insert(QcDept().apply { id = deptId; name = "Engineering" })
            QcEmps.insert(QcEmp().apply { id = empId; this.deptId = deptId; name = "Ada" })
        }

        val rows = db.autocommit {
            (QcDepts innerJoin QcEmps on (QcDepts.id eq QcEmps.deptId)).select()
        }
        assertEquals(1, rows.size)
        val row = rows.single()
        // The colliding "name" / "id" columns must resolve to their own table's value.
        assertEquals("Engineering", row[QcDepts.name])
        assertEquals("Ada", row[QcEmps.name])
        assertEquals(deptId, row[QcDepts.id])
        assertEquals(empId, row[QcEmps.id])

        // Entity-pair hydration must also keep the two same-named columns apart.
        val pairs = db.autocommit {
            (QcDepts innerJoin QcEmps on (QcDepts.id eq QcEmps.deptId)).find()
        }
        assertEquals(1, pairs.size)
        assertEquals("Engineering", pairs.single().first.name)
        assertEquals("Ada", pairs.single().second.name)
        assertEquals(deptId, pairs.single().first.id)
        assertEquals(empId, pairs.single().second.id)

        db.transaction {
            QcEmps.deleteWhere(Query(QcEmps.id eq empId))
            QcDepts.deleteWhere(Query(QcDepts.id eq deptId))
        }
    }

    /**
     * A projection that selects the same-named column from *both* tables (an explicit field
     * list) must read each one back independently rather than collapsing them.
     */
    @Test
    fun projectionOfBothCollidingColumns() {
        val deptId = Uuid.random()
        db.transaction {
            QcDepts.execSql(qcDeptsDdl)
            QcEmps.execSql(qcEmpsDdl)
            QcDepts.insert(QcDept().apply { id = deptId; name = "Sales" })
            QcEmps.insert(QcEmp().apply { id = Uuid.random(); this.deptId = deptId; name = "Grace" })
        }
        val pair = db.autocommit {
            (QcDepts innerJoin QcEmps on (QcDepts.id eq QcEmps.deptId))
                .select(QcDepts.name, QcEmps.name) { it[QcDepts.name] to it[QcEmps.name] }
        }
        assertEquals(listOf("Sales" to "Grace"), pair)
        db.transaction {
            QcEmps.deleteWhere { where { QcEmps.deptId eq deptId } }
            QcDepts.deleteWhere(Query(QcDepts.id eq deptId))
        }
    }

    /**
     * Reading a field that the projection did not select must fail with clear guidance ("was not
     * selected — add it to select(...)"), distinct from the message for a selected-but-NULL field.
     */
    @Test
    fun readingUnselectedProjectionFieldGivesActionableError() {
        val deptId = Uuid.random()
        db.transaction {
            QcDepts.execSql(qcDeptsDdl)
            QcEmps.execSql(qcEmpsDdl)
            QcDepts.insert(QcDept().apply { id = deptId; name = "Ops" })
            QcEmps.insert(QcEmp().apply { id = Uuid.random(); this.deptId = deptId; name = "Lin" })
        }
        val row = db.autocommit {
            (QcDepts innerJoin QcEmps on (QcDepts.id eq QcEmps.deptId)).select(QcDepts.name).single()
        }
        val ex = assertFailsWith<IllegalStateException> { row[QcEmps.id] }
        val msg = ex.message ?: ""
        assertTrue("not selected" in msg, "should explain the field was not selected: $msg")
        db.transaction {
            QcEmps.deleteWhere { where { QcEmps.deptId eq deptId } }
            QcDepts.deleteWhere(Query(QcDepts.id eq deptId))
        }
    }

    // ---- 2. Aggregations + HAVING ----------------------------------------------------------

    /** count/sum/avg/min/max over a single group, all read from one row. */
    @Test
    fun aggregateFunctionsOverAGroup() {
        val tag = "agg-${Uuid.random()}"
        db.transaction {
            QcSales.execSql(qcSalesDdl)
            QcSales.insertAll(listOf(10, 20, 30).map { amt ->
                QcSale().apply { id = Uuid.random(); region = tag; amount = amt }
            })
        }
        val cnt = count()
        val total = QcSales.amount.sum()
        val mean = QcSales.amount.avg()
        val lo = QcSales.amount.min()
        val hi = QcSales.amount.max()
        val row = db.autocommit {
            QcSales.query().where(QcSales.region eq tag).select(cnt, total, mean, lo, hi)
        }.single()
        assertEquals(3L, row[cnt])
        assertEquals(60L, row[total]) // sum() over an Int column is read as Long
        assertEquals(20.0, row[mean])
        assertEquals(10, row[lo])
        assertEquals(30, row[hi])
        db.transaction { QcSales.deleteWhere(Query(QcSales.region eq tag)) }
    }

    /** GROUP BY region + COUNT(*) buckets rows per group. */
    @Test
    fun groupByCountsPerGroup() {
        val a = "ga-${Uuid.random()}"
        val b = "gb-${Uuid.random()}"
        db.transaction {
            QcSales.execSql(qcSalesDdl)
            QcSales.insertAll(
                listOf(
                    QcSale().apply { id = Uuid.random(); region = a; amount = 1 },
                    QcSale().apply { id = Uuid.random(); region = a; amount = 2 },
                    QcSale().apply { id = Uuid.random(); region = b; amount = 3 },
                )
            )
        }
        val cnt = count()
        val byRegion = db.autocommit {
            QcSales.query()
                .where((QcSales.region eq a) or (QcSales.region eq b))
                .groupBy(QcSales.region)
                .select(QcSales.region, cnt)
        }.associate { it[QcSales.region] to it[cnt] }
        assertEquals(mapOf(a to 2L, b to 1L), byRegion)
        db.transaction {
            QcSales.deleteWhere(Query(QcSales.region eq a))
            QcSales.deleteWhere(Query(QcSales.region eq b))
        }
    }

    /**
     * HAVING filters out groups *after* aggregation: only regions whose COUNT(*) >= 2 survive.
     * The HAVING predicate is built from an aggregate Selectable (`count() gtEq Value(2)`).
     */
    @Test
    fun havingFiltersGroupsAfterAggregation() {
        val many = "hm-${Uuid.random()}" // 3 rows
        val few = "hf-${Uuid.random()}"  // 1 row
        db.transaction {
            QcSales.execSql(qcSalesDdl)
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
        val survivors = db.autocommit {
            QcSales.query()
                .where((QcSales.region eq many) or (QcSales.region eq few))
                .groupBy(QcSales.region)
                .having(cnt gtEq Value(2))
                .select(QcSales.region, cnt)
        }.associate { it[QcSales.region] to it[cnt] }
        // Only the "many" group (3 rows) clears HAVING COUNT(*) >= 2.
        assertEquals(mapOf(many to 3L), survivors)
        assertTrue(few !in survivors, "a group below the HAVING threshold must be excluded")
        db.transaction {
            QcSales.deleteWhere(Query(QcSales.region eq many))
            QcSales.deleteWhere(Query(QcSales.region eq few))
        }
    }

    /** HAVING on SUM (not just COUNT): keeps groups whose summed amount clears a threshold. */
    @Test
    fun havingOnSumAggregate() {
        val rich = "sr-${Uuid.random()}" // sum 100
        val poor = "sp-${Uuid.random()}" // sum 5
        db.transaction {
            QcSales.execSql(qcSalesDdl)
            QcSales.insertAll(
                listOf(
                    QcSale().apply { id = Uuid.random(); region = rich; amount = 60 },
                    QcSale().apply { id = Uuid.random(); region = rich; amount = 40 },
                    QcSale().apply { id = Uuid.random(); region = poor; amount = 5 },
                )
            )
        }
        val total = QcSales.amount.sum()
        val survivors = db.autocommit {
            QcSales.query()
                .where((QcSales.region eq rich) or (QcSales.region eq poor))
                .groupBy(QcSales.region)
                .having(total gtEq Value(50))
                .select(QcSales.region, total)
        }.associate { it[QcSales.region] to it[total] }
        assertEquals(mapOf(rich to 100L), survivors)
        db.transaction {
            QcSales.deleteWhere(Query(QcSales.region eq rich))
            QcSales.deleteWhere(Query(QcSales.region eq poor))
        }
    }

    // ---- 3. SQLSTATE / constraint-violation mapping ---------------------------------------

    /** A UNIQUE (non-PK) constraint maps to UniqueViolationException. */
    @Test
    fun uniqueConstraintViolationIsTyped() {
        db.transaction {
            QcUnique.execSql(qcUniqueDdl)
            QcUnique.insert(QcUniqueRow().apply { id = Uuid.random(); email = "a@b.c" })
        }
        val ex = assertFailsWith<UniqueViolationException> {
            db.transaction {
                QcUnique.insert(QcUniqueRow().apply { id = Uuid.random(); email = "a@b.c" })
            }
        }
        // The mapper carries the (extended) SQLite code in sqlState.
        assertTrue(ex.sqlState != null, "a mapped unique violation should carry a sqlState code")
        db.transaction { QcUnique.deleteWhere(Query(QcUnique.email eq "a@b.c")) }
    }

    /** A NOT NULL constraint maps to NotNullViolationException. */
    @Test
    fun notNullConstraintViolationIsTyped() {
        db.transaction { QcNotNull.execSql(qcNotNullDdl) }
        assertFailsWith<NotNullViolationException> {
            db.transaction {
                // Insert via raw SQL so we can omit the NOT NULL "label" column entirely.
                executeUpdate(
                    "INSERT INTO qc_notnull (id) VALUES (:id)",
                    params = mapOf("id" to Uuid.random().toString()),
                    invalidates = emptyList(),
                )
            }
        }
    }

    /** A foreign-key violation maps to ForeignKeyViolationException (foreign_keys pragma ON). */
    @Test
    fun foreignKeyViolationIsTyped() {
        db.transaction {
            executeUpdate("CREATE TABLE IF NOT EXISTS qc_fk_parent (id TEXT PRIMARY KEY)", params = emptyMap(), invalidates = emptyList())
            executeUpdate(
                "CREATE TABLE IF NOT EXISTS qc_fk_child (" +
                    "id TEXT PRIMARY KEY, parent_id TEXT REFERENCES qc_fk_parent(id))",
                params = emptyMap(),
                invalidates = emptyList(),
            )
        }
        assertFailsWith<ForeignKeyViolationException> {
            db.transaction {
                executeUpdate(
                    "INSERT INTO qc_fk_child (id, parent_id) VALUES (:id, :p)",
                    params = mapOf("id" to Uuid.random().toString(), "p" to Uuid.random().toString()),
                    invalidates = emptyList(),
                )
            }
        }
    }

    /** A CHECK constraint maps to CheckViolationException. */
    @Test
    fun checkConstraintViolationIsTyped() {
        db.transaction { QcCheck.execSql(qcCheckDdl) }
        val ex = assertFailsWith<CheckViolationException> {
            db.transaction {
                QcCheck.insert(QcCheckRow().apply { id = Uuid.random(); amount = -1 })
            }
        }
        assertTrue(ex.sqlState != null, "a mapped check violation should carry a sqlState code")
    }
}

object QcCatalog : Catalog

// dept and emp deliberately share the column names "id" and "name".
class QcDept : Entity() {
    var id by QcDepts.id
    var name by QcDepts.name
}

object QcDepts : Table<QcCatalog, QcDept>("qc_depts", ::QcDept) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()

    init { id; name }
}

class QcEmp : Entity() {
    var id by QcEmps.id
    var deptId by QcEmps.deptId
    var name by QcEmps.name
}

object QcEmps : Table<QcCatalog, QcEmp>("qc_emps", ::QcEmp) {
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

object QcSales : Table<QcCatalog, QcSale>("qc_sales", ::QcSale) {
    val id by Column.UUID().primaryKey()
    val region by Column.Text()
    val amount by Column.Int()

    init { id; region; amount }
}

class QcUniqueRow : Entity() {
    var id by QcUnique.id
    var email by QcUnique.email
}

object QcUnique : Table<QcCatalog, QcUniqueRow>("qc_unique", ::QcUniqueRow) {
    val id by Column.UUID().primaryKey()
    val email by Column.Text()

    init { id; email }
}

class QcNotNullRow : Entity() {
    var id by QcNotNull.id
    var label by QcNotNull.label
}

object QcNotNull : Table<QcCatalog, QcNotNullRow>("qc_notnull", ::QcNotNullRow) {
    val id by Column.UUID().primaryKey()
    val label by Column.Text()

    init { id; label }
}

class QcCheckRow : Entity() {
    var id by QcCheck.id
    var amount by QcCheck.amount
}

object QcCheck : Table<QcCatalog, QcCheckRow>("qc_check", ::QcCheckRow) {
    val id by Column.UUID().primaryKey()
    val amount by Column.Int()

    init { id; amount }
}

// Raw schema DDL (Kormium does not own schema management). SQLite type affinity.
internal val qcDeptsDdl = """CREATE TABLE IF NOT EXISTS "qc_depts" ("id" TEXT NOT NULL, "name" TEXT NOT NULL, PRIMARY KEY ("id"))"""
internal val qcEmpsDdl = """CREATE TABLE IF NOT EXISTS "qc_emps" ("id" TEXT NOT NULL, "deptId" TEXT NOT NULL, "name" TEXT NOT NULL, PRIMARY KEY ("id"))"""
internal val qcSalesDdl = """CREATE TABLE IF NOT EXISTS "qc_sales" ("id" TEXT NOT NULL, "region" TEXT NOT NULL, "amount" INTEGER NOT NULL, PRIMARY KEY ("id"))"""
internal val qcUniqueDdl = """CREATE TABLE IF NOT EXISTS "qc_unique" ("id" TEXT NOT NULL, "email" TEXT NOT NULL UNIQUE, PRIMARY KEY ("id"))"""
internal val qcNotNullDdl = """CREATE TABLE IF NOT EXISTS "qc_notnull" ("id" TEXT NOT NULL, "label" TEXT NOT NULL, PRIMARY KEY ("id"))"""
internal val qcCheckDdl = """CREATE TABLE IF NOT EXISTS "qc_check" ("id" TEXT NOT NULL, "amount" INTEGER NOT NULL CHECK ("amount" >= 0), PRIMARY KEY ("id"))"""
