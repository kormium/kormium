import kotlin.uuid.Uuid
import io.github.kormium.*
import io.github.kormium.database.Database
import io.github.kormium.database.SuspendDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestEntity : Entity() {
    var id by TestTable.id
    var price by TestTable.price
    var position by TestTable.position
    var text by TestTable.text
    var nullableTest by TestTable.nullableTest
}

object TestCatalog : Catalog

object TestTable : Table<TestCatalog, TestEntity>("products", ::TestEntity) {
    val id by Column.UUID()
    val price by Column.Double()
    val position by Column.Int()
    val text by Column.Text()
    val nullableTest by Column.Text().nullable()
}


class TestOrderEntity : Entity() {
    var orderId by TestOrders.orderId
    var productId by TestOrders.productId
}

object TestOrders : Table<TestCatalog, TestOrderEntity>("orders", ::TestOrderEntity) {
    val orderId by Column.UUID()
    val productId by Column.UUID()
}

class CodedEntity : Entity() {
    var code by Coded.code
    var amount by Coded.amount
}

object Coded : Table<TestCatalog, CodedEntity>("coded", ::CodedEntity) {
    val code by Column.Text().primaryKey()
    val amount by Column.Int()
}

class CompositeKeyEntity : Entity() {
    var left by CompositeKey.left
    var right by CompositeKey.right
}

object CompositeKey : Table<TestCatalog, CompositeKeyEntity>("composite", ::CompositeKeyEntity) {
    val left by Column.UUID().primaryKey()
    val right by Column.Int().primaryKey()
}

// Two tables parameterized with the SAME entity type: a column of one type-checks as a conflict
// target for the other (both are Column<*, *, SharedRow>), exercising the runtime ownership
// backstop that the compile-time `Column<*, *, T>` constraint cannot catch.
class SharedRow : Entity() {
    var a by SharedA.a
}

object SharedA : Table<TestCatalog, SharedRow>("shared_a", ::SharedRow) {
    val a by Column.Int()
}

object SharedB : Table<TestCatalog, SharedRow>("shared_b", ::SharedRow) {
    val b by Column.Int()
}

class NamedEntity : Entity() {
    var id by Named.id
    var createdAt by Named.createdAt
}

object Named : Table<TestCatalog, NamedEntity>("named", ::NamedEntity) {
    val id by Column.UUID().primaryKey()
    val createdAt by Column.Instant(name = "created_at")
}

class TableTest {

    @Test
    fun testColumnsRegisterWhenDeclared() {
        assertEquals(
            listOf("id", "price", "position", "text", "nullableTest"),
            TestTable.getFieldDisplayNames().keys.toList(),
        )
        assertEquals(TestTable.id, TestTable.getFieldDisplayNames()["id"])
        assertEquals(listOf(TestTable.id), TestTable.primaryKey)
    }

    // Regression (#33): isSet/unset take `Column<*, *, N>`, so a column from a differently-typed
    // table is rejected at COMPILE time — `TestEntity().isSet(TestOrders.orderId)` does not compile
    // (TestOrders' columns carry TestOrderEntity, not TestEntity). That guarantee can't be expressed
    // as a runtime test; this test pins the absent/explicit-null/value semantics that stay unchanged.
    @Test
    fun testIsSetAndUnset() {
        val e = TestEntity()
        // Never assigned → absent.
        assertFalse(e.isSet(TestTable.nullableTest))
        // Explicit null counts as set.
        e.nullableTest = null
        assertTrue(e.isSet(TestTable.nullableTest))
        assertEquals(null, e.nullableTest)
        // unset() returns it to absent.
        e.unset(TestTable.nullableTest)
        assertFalse(e.isSet(TestTable.nullableTest))
        // A concrete value is set.
        e.text = "hi"
        assertTrue(e.isSet(TestTable.text))
        // A column from another entity of the same catalog also type-checks only for its own entity.
        val order = TestOrderEntity()
        assertFalse(order.isSet(TestOrders.orderId))
        order.orderId = Uuid.random()
        assertTrue(order.isSet(TestOrders.orderId))
        order.unset(TestOrders.orderId)
        assertFalse(order.isSet(TestOrders.orderId))
    }

    @Test
    fun testCustomColumnNameSplitsSqlNameFromFieldKey() {
        // The SQL identifier uses the custom name; the entity field key follows the property.
        assertEquals("created_at", Named.createdAt.name)
        assertEquals("createdAt", Named.createdAt.fieldKey)
        assertTrue(Named.getFieldDisplayNames().containsKey("createdAt"))
        assertFalse(Named.getFieldDisplayNames().containsKey("created_at"))

        // INSERT renders the custom SQL name.
        db.transaction {
            Named.insert(NamedEntity().apply {
                id = Uuid.random()
                createdAt = kotlin.time.Clock.System.now()
            })
        }
        assertTrue(
            databaseMockObj.internalSql.contains("\"created_at\""),
            "expected custom SQL name in: ${databaseMockObj.internalSql}",
        )
    }

    @Test
    fun testFindBlockDslMatchesQuery() {
        val price = 100.0
        // Block DSL: two where{} blocks AND together, ordering + limit/offset.
        db.transaction {
            TestTable.find {
                where { TestTable.price eq price }
                where { TestTable.position gtEq 1 }
                orderBy DESC TestTable.position
                limit = 50
                offset = 10
            }
        }
        val blockSql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        val blockParams = databaseMockObj.internalParams

        // Equivalent explicit Query(...) value form.
        db.transaction {
            TestTable.find(
                Query(
                    whereExpression = (TestTable.price eq price) and (TestTable.position gtEq 1),
                    orderBy = mapOf(TestTable.position to AscDescOrder.DESC),
                    limit = 50u,
                    offset = 10u,
                )
            )
        }
        // The block DSL parenthesizes AND-combined blocks; assert structure rather than
        // exact equality, and that both forms select the same params.
        assertTrue(blockSql.contains("""("price"=:p0)AND("position">=:p1)"""), blockSql)
        assertTrue(blockSql.contains("""ORDERBY"position"DESC"""), blockSql)
        assertTrue(blockSql.contains("LIMIT50"), blockSql)
        assertTrue(blockSql.contains("OFFSET10"), blockSql)
        assertEquals(mapOf("p0" to 100.0, "p1" to 1), blockParams)
    }

    @Test
    fun testEmptyFindBlockIsAllRows() {
        db.transaction { TestTable.find { } }
        assertFalse(databaseMockObj.internalSql.contains("WHERE"), "empty block must not emit WHERE")
        assertTrue(databaseMockObj.internalParams.isEmpty())
    }

    @Test
    fun testNegativeLimitIsRejected() {
        // Regression: a negative limit must fail fast instead of wrapping via toUInt() into
        // a huge LIMIT (-1 -> 4294967295).
        assertFailsWith<IllegalArgumentException> {
            db.transaction { TestTable.find { limit = -1 } }
        }
    }

    @Test
    fun testNegativeOffsetIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            db.transaction { TestTable.find { offset = -1 } }
        }
    }

    @Test
    fun testNullPredicates() {
        db.transaction { TestTable.find(Query(TestTable.nullableTest eq null)) }
        assertTrue(remoteNewLinesAndSpaces(databaseMockObj.internalSql).contains(""""nullableTest"ISNULL"""))
        assertTrue(databaseMockObj.internalParams.isEmpty())

        db.transaction { TestTable.find(Query(TestTable.nullableTest neq null)) }
        assertTrue(remoteNewLinesAndSpaces(databaseMockObj.internalSql).contains(""""nullableTest"ISNOTNULL"""))
    }

    @Test
    fun testInsertOmitsAbsentFields() {
        // nullableTest is never assigned → it must not appear in the INSERT.
        db.transaction {
            TestTable.insert(TestEntity().apply {
                id = Uuid.random(); price = 1.0; position = 1; text = "x"
            })
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""("id","price","position","text")"""), sql)
        assertFalse(sql.contains("nullableTest"), sql)
    }

    @Test
    fun testInsertEmptyEntityUsesDefaultValues() {
        db.transaction { TestTable.insert(TestEntity()) }
        assertEquals(
            """INSERTINTO"products"DEFAULTVALUES""",
            remoteNewLinesAndSpaces(databaseMockObj.internalSql),
        )
    }

    @Test
    fun testUpdateBlockDslReturnsAffectedRows() {
        val uuid = Uuid.random()
        databaseMockObj.result = 1L
        val affected = db.transaction {
            TestTable.update(TestEntity().apply { position = 9 }) {
                where { TestTable.id eq uuid }
            }
        }
        assertEquals(1L, affected)
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""UPDATE"products"SET"position"=:p0"""), sql)
        assertTrue(sql.contains("""WHERE"id"=:p1"""), sql)
        databaseMockObj.result = null
    }

    @Test
    fun testExpressionUpdateAtomicIncrement() {
        val uuid = Uuid.random()
        databaseMockObj.result = 1L
        val affected = db.transaction {
            TestTable.update {
                TestTable.position set (TestTable.position + 1)
                where { TestTable.id eq uuid }
            }
        }
        assertEquals(1L, affected)
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""UPDATE"products"SET"position"="position"+:p0"""), sql)
        assertTrue(sql.contains("""WHERE"id"=:p1"""), sql)
        assertEquals(1, databaseMockObj.internalParams["p0"])
        databaseMockObj.result = null
    }

    @Test
    fun testExpressionUpdateNestedArithmeticAndMixedAssignments() {
        databaseMockObj.result = 1L
        db.transaction {
            TestTable.update {
                TestTable.position set ((TestTable.position + 2) * 3)
                TestTable.text set "x"
            }
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        // The nested arithmetic operand is parenthesized; assignments keep their order.
        assertTrue(sql.contains("""SET"position"=("position"+:p0)*:p1,"text"=:p2"""), sql)
        databaseMockObj.result = null
    }

    @Test
    fun testExpressionUpdateColumnTimesColumn() {
        databaseMockObj.result = 1L
        db.transaction { TestTable.update { TestTable.position set (TestTable.position * TestTable.position) } }
        assertTrue(
            remoteNewLinesAndSpaces(databaseMockObj.internalSql).contains("""SET"position"="position"*"position""""),
            databaseMockObj.internalSql,
        )
        databaseMockObj.result = null
    }

    @Test
    fun testArithmeticInWhereClause() {
        db.transaction { TestTable.find { where { (TestTable.position + 1) gtEq 10 } } }
        assertTrue(
            remoteNewLinesAndSpaces(databaseMockObj.internalSql).contains(""""position"+:p0>=:p1"""),
            databaseMockObj.internalSql,
        )
    }

    @Test
    fun testExpressionUpdateRejectsEmptySet() {
        assertFailsWith<IllegalArgumentException> {
            db.transaction { TestTable.update { where { TestTable.id eq Uuid.random() } } }
        }
    }

    @Test
    fun testDeleteBlockDslReturnsAffectedRows() {
        databaseMockObj.result = 2L
        val affected = db.transaction { TestTable.deleteWhere { where { TestTable.position eq 5 } } }
        assertEquals(2L, affected)
        assertEquals(
            """DELETEFROM"products"WHERE"position"=:p0""",
            remoteNewLinesAndSpaces(databaseMockObj.internalSql),
        )
        databaseMockObj.result = null
    }

    @Test
    fun testStrictBatchRejectsDifferentShapes() {
        assertFailsWith<IllegalArgumentException> {
            db.transaction {
                TestTable.insertAll(
                    listOf(
                        TestEntity().apply { id = Uuid.random(); price = 1.0; position = 1; text = "a" },
                        TestEntity().apply { id = Uuid.random(); price = 1.0; position = 2; text = "b"; nullableTest = "x" },
                    ),
                    batchInsertMode = BatchInsertMode.Strict,
                )
            }
        }
    }

    @Test
    fun testUnionNullsBatchUsesUnionOfColumns() {
        db.transaction {
            TestTable.insertAll(
                listOf(
                    TestEntity().apply { id = Uuid.random(); price = 1.0; position = 1; text = "a" },
                    TestEntity().apply { id = Uuid.random(); price = 1.0; position = 2; text = "b"; nullableTest = "x" },
                ),
                batchInsertMode = BatchInsertMode.UnionNulls,
            )
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        // One statement: the union of all assigned columns, two value tuples.
        assertTrue(sql.contains("""("id","price","position","text","nullableTest")VALUES"""), sql)
        assertTrue(sql.contains("),("), sql)
    }

    @Test
    fun testUpsertSql() {
        db.transaction {
            TestTable.upsert(
                entity = TestEntity().apply { id = Uuid.random(); price = 1.0; position = 1; text = "a" },
                onConflict = TestTable.id,
                update = TestEntity().apply { position = 2 },
            )
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""ONCONFLICT("id")DOUPDATESET"position"="""), sql)
    }

    @Test
    fun testInsertOrIgnoreSql() {
        databaseMockObj.result = 1L
        val n = db.transaction {
            TestTable.insertOrIgnore(
                TestEntity().apply { id = Uuid.random(); price = 1.0; position = 1; text = "a" },
                onConflict = TestTable.id,
            )
        }
        assertEquals(1L, n)
        assertTrue(remoteNewLinesAndSpaces(databaseMockObj.internalSql).contains("""ONCONFLICT("id")DONOTHING"""))
        databaseMockObj.result = null
    }

    @Test
    fun testUpsertRejectsEmptyConflictTarget() {
        // Regression (#30): an empty conflict list would render invalid SQL `ON CONFLICT ()`.
        assertFailsWith<IllegalArgumentException> {
            db.transaction {
                TestTable.upsert(
                    entity = TestEntity().apply { id = Uuid.random(); price = 1.0; position = 1; text = "a" },
                    onConflict = emptyList(),
                    update = TestEntity().apply { position = 2 },
                )
            }
        }
    }

    @Test
    fun testInsertOrIgnoreRejectsEmptyConflictTarget() {
        assertFailsWith<IllegalArgumentException> {
            db.transaction {
                TestTable.insertOrIgnore(
                    TestEntity().apply { id = Uuid.random(); price = 1.0; position = 1; text = "a" },
                    onConflict = emptyList(),
                )
            }
        }
    }

    // Regression (#32): a conflict column from a *differently-typed* table is rejected at COMPILE
    // time — `onConflict` is `Column<*, *, T>`, so `TestTable.upsert(onConflict = TestOrders.orderId)`
    // does not compile (TestOrders' columns carry TestOrderEntity, not TestEntity). That guarantee
    // can't be expressed as a runtime test; the case below covers what types *can't*: a foreign
    // column from a table that shares this entity type, caught by the runtime backstop.
    @Test
    fun testUpsertRejectsConflictColumnFromSameEntityOtherTable() {
        val ex = assertFailsWith<IllegalArgumentException> {
            db.transaction {
                // SharedA and SharedB are both Table<_, SharedRow>, so SharedB.b type-checks as a
                // conflict target for SharedA but does not belong to it.
                SharedA.upsert(
                    entity = SharedRow().apply { a = 1 },
                    onConflict = SharedB.b,
                    update = SharedRow().apply { a = 2 },
                )
            }
        }
        val msg = ex.message ?: ""
        assertTrue("shared_b" in msg && "shared_a" in msg, "message should name both tables: $msg")
    }

    @Test
    fun testCountIgnoresOrderLimitOffset() {
        // Regression (#29): count must ignore ORDER BY / LIMIT / OFFSET — an OFFSET would skip
        // the single COUNT row and read as 0.
        db.transaction {
            TestTable.count(
                Query(
                    whereExpression = TestTable.position eq 1,
                    limit = 10u,
                    offset = 10u,
                    orderBy = mapOf(TestTable.position to AscDescOrder.ASC),
                )
            )
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("SELECTCOUNT(*)"), sql)
        assertTrue(sql.contains(""""position"=:p0"""), sql)
        assertFalse(sql.contains("ORDERBY"), sql)
        assertFalse(sql.contains("LIMIT"), sql)
        assertFalse(sql.contains("OFFSET"), sql)
        assertEquals(mapOf("p0" to 1), databaseMockObj.internalParams)
    }

    @Test
    fun testInsert() {
        val uuid = Uuid.random()
        val price = 100.0
        val position = 1
        val text = "hello world"
        val expectedResult = """INSERT INTO "products"
                        ("id", "price", "position", "text", "nullableTest")
                        VALUES (:p0, :p1, :p2, :p3, :p4)"""
        db.transaction {
            TestTable.insert(TestEntity().apply {
                this.id = uuid
                this.price = price
                this.position = position
                this.text = text
                this.nullableTest = null
            })
        }
        assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
        assertEquals(
            mapOf(
                "p0" to uuid.toString(),
                "p1" to 100.0,
                "p2" to position,
                "p3" to text,
                "p4" to null,
            ),
            databaseMockObj.internalParams,
        )
    }

    @Test
    fun testInsertReturning() {
        val expectedResult = """INSERT INTO "products"
                        ("id", "price", "position", "text", "nullableTest")
                        VALUES (:p0, :p1, :p2, :p3, :p4)
                        RETURNING "id", "price", "position", "text", "nullableTest""""
        val entity = TestEntity().apply {
            this.id = Uuid.random()
            this.price = 1.0
            this.position = 1
            this.text = "x"
            this.nullableTest = null
        }
        databaseMockObj.result = listOf(entity)   // RETURNING yields the written row back (insert is non-null)
        try {
            val returned = db.transaction { TestTable.insert(entity, returning = true) }
            assertEquals(entity, returned)
            assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
        } finally {
            databaseMockObj.result = null
        }
    }

    @Test
    fun testUpdate() {
        val uuid = Uuid.random()
        val price = 100.0
        val position = 1
        val text = "hello world"
        val expectedResult = """
            UPDATE "products"
            SET "id"=:p0, "price"=:p1, "position"=:p2, "text"=:p3, "nullableTest"=:p4
            WHERE "id" = :p5
        """
        db.transaction {
            TestTable.update(TestEntity().apply {
                this.id = uuid
                this.price = price
                this.position = position
                this.text = text
                this.nullableTest = null
            }, Query(TestTable.id eq uuid))
        }
        assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
        assertEquals(
            mapOf(
                "p0" to uuid.toString(),
                "p1" to 100.0,
                "p2" to position,
                "p3" to text,
                "p4" to null,
                "p5" to uuid.toString(),
            ),
            databaseMockObj.internalParams,
        )
    }

    /**
     * Regression: a field explicitly set to null must be written as NULL (it used to be
     * dropped from SET), while fields left untouched are still omitted (partial update).
     */
    @Test
    fun testUpdateCanSetNullAndOmitsUntouched() {
        val uuid = Uuid.random()
        val expectedResult = """UPDATE "products" SET "nullableTest"=:p0 WHERE "id" = :p1"""
        db.transaction {
            TestTable.update(TestEntity().apply { this.nullableTest = null }, Query(TestTable.id eq uuid))
        }
        assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
        assertEquals(mapOf("p0" to null, "p1" to uuid.toString()), databaseMockObj.internalParams)
    }

    @Test
    fun testSelect() {
        val price = 100.0
        val count = 10u
        val from = 5u
        val expectedResult = """
            SELECT "id", "price", "position", "text", "nullableTest" FROM "products"
            WHERE "price" = :p0 ORDER BY "position" ASC LIMIT $count OFFSET $from
        """
        db.transaction {
            TestTable.find(
                Query(
                    whereExpression = TestTable.price eq price,
                    limit = count,
                    offset = from,
                    orderBy = mapOf(TestTable.position to AscDescOrder.ASC),
                )
            )
        }
        assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
        assertEquals(mapOf("p0" to 100.0), databaseMockObj.internalParams)
    }

    @Test
    fun testFindOneByPrimaryKey() {
        val uuid = Uuid.random()
        val expectedResult = """SELECT "id", "price", "position", "text", "nullableTest" FROM "products" WHERE "id" = :p0 LIMIT 1"""
        db.transaction { TestTable.findOne { where { TestTable.id eq uuid } } }
        assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
        assertEquals(mapOf("p0" to uuid.toString()), databaseMockObj.internalParams)
    }

    @Test
    fun testDeleteWhere() {
        val expectedResult = """DELETE FROM "products" WHERE "position" = :p0"""
        db.transaction { TestTable.deleteWhere(Query(TestTable.position eq 5)) }
        assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
        assertEquals(mapOf("p0" to 5), databaseMockObj.internalParams)
    }

    @Test
    fun testCompoundWhereSharesOneParameterSpace() {
        val expectedResult = """
            SELECT "id", "price", "position", "text", "nullableTest" FROM "products"
            WHERE "position" = :p0 AND "text" = :p1
        """
        db.transaction {
            TestTable.find(Query(TestTable.position eq 1 and (TestTable.text eq "abc")))
        }
        assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
        assertEquals(mapOf("p0" to 1, "p1" to "abc"), databaseMockObj.internalParams)
    }

    @Test
    fun testMixedAndOrKeepsKotlinEvaluationOrder() {
        // Regression: Kotlin infix `or`/`and` are same-precedence and left-associative, so
        // `a or b and c` builds AndOp(OrOp(a, b), c). Without parentheses SQL would parse it
        // as `a OR (b AND c)` (AND binds tighter) — a silently different result set.
        val expectedResult = """
            SELECT "id", "price", "position", "text", "nullableTest" FROM "products"
            WHERE ("position" = :p0 OR "position" = :p1) AND "text" = :p2
        """
        db.transaction {
            TestTable.find(
                Query(TestTable.position eq 1 or (TestTable.position eq 2) and (TestTable.text eq "abc"))
            )
        }
        assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
        assertEquals(mapOf("p0" to 1, "p1" to 2, "p2" to "abc"), databaseMockObj.internalParams)
    }

    @Test
    fun testSameOperatorChainStaysFlat() {
        // Associative chains need no parentheses: `a and b and c` renders flat.
        val expectedResult = """
            SELECT "id", "price", "position", "text", "nullableTest" FROM "products"
            WHERE "position" = :p0 AND "text" = :p1 AND "position" = :p2
        """
        db.transaction {
            TestTable.find(
                Query(TestTable.position eq 1 and (TestTable.text eq "abc") and (TestTable.position eq 3))
            )
        }
        assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
        assertEquals(mapOf("p0" to 1, "p1" to "abc", "p2" to 3), databaseMockObj.internalParams)
    }

    @Test
    fun testSavepointInAutocommitFailsFast() {
        // A savepoint without a surrounding transaction is a server error on Postgres and
        // backend-dependent elsewhere — fail uniformly with a clear message instead.
        assertFailsWith<IllegalStateException> {
            db.autocommit { savepoint { } }
        }
    }

    @Test
    fun testUpdateWithNoNonNullFieldsFails() {
        assertFailsWith<IllegalArgumentException> {
            db.transaction { TestTable.update(TestEntity(), Query(TestTable.id eq Uuid.random())) }
        }
    }

    @Test
    fun testUpdateIgnoresOrderLimitOffset() {
        // Regression (#44): a plain UPDATE must not emit ORDER BY / LIMIT / OFFSET (invalid in Postgres).
        db.transaction {
            TestTable.update(TestEntity().apply { position = 9 }) {
                where { TestTable.id eq Uuid.random() }
                orderBy ASC TestTable.position
                limit = 1
                offset = 5
            }
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.startsWith("""UPDATE"products"SET"position"=:p0WHERE"""), sql)
        assertFalse(sql.contains("ORDERBY"), sql)
        assertFalse(sql.contains("LIMIT"), sql)
        assertFalse(sql.contains("OFFSET"), sql)
    }

    @Test
    fun testDeleteIgnoresOrderLimitOffset() {
        // Regression (#44): a plain DELETE must not emit ORDER BY / LIMIT / OFFSET.
        db.transaction {
            TestTable.deleteWhere {
                where { TestTable.position eq 5 }
                orderBy DESC TestTable.position
                limit = 1
                offset = 5
            }
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.startsWith("""DELETEFROM"products"WHERE"""), sql)
        assertFalse(sql.contains("ORDERBY"), sql)
        assertFalse(sql.contains("LIMIT"), sql)
        assertFalse(sql.contains("OFFSET"), sql)
    }

    /**
     * Regression test for the bug where a [Query] with no `where` rendered the
     * literal string "null" into the SQL. An empty query must produce no WHERE.
     */
    @Test
    fun testEmptyQueryHasNoWhereClause() {
        val expectedResult = """SELECT "id", "price", "position", "text", "nullableTest" FROM "products""""
        db.transaction { TestTable.find(Query()) }
        assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
        assertFalse(databaseMockObj.internalSql.contains("WHERE"), "empty query must not emit WHERE")
        assertTrue(databaseMockObj.internalParams.isEmpty())
    }

    /**
     * The whole point of parameterization: a value that looks like an injection
     * payload must land in the bind parameters, never inlined into the SQL text.
     */
    @Test
    fun testValuesAreParameterizedNotInlined() {
        val payload = "x'; DROP TABLE products; --"
        db.transaction { TestTable.find(Query(TestTable.text eq payload)) }
        assertFalse(
            databaseMockObj.internalSql.contains("DROP TABLE"),
            "the value must not be inlined into SQL: ${databaseMockObj.internalSql}",
        )
        assertTrue(databaseMockObj.internalSql.contains(":p0"))
        assertEquals(payload, databaseMockObj.internalParams["p0"])
    }

    @Test
    fun testInListLikeIsNullAndNotOperators() {
        db.transaction { TestTable.find(Query(TestTable.position inList listOf(1, 2))) }
        assertTrue(remoteNewLinesAndSpaces(databaseMockObj.internalSql).contains(""""position"IN(:p0,:p1)"""))
        assertEquals(mapOf("p0" to 1, "p1" to 2), databaseMockObj.internalParams)

        db.transaction { TestTable.find(Query(TestTable.position inList emptyList())) }
        assertTrue(remoteNewLinesAndSpaces(databaseMockObj.internalSql).contains("WHEREFALSE"))

        db.transaction { TestTable.find(Query(TestTable.text like "ab%")) }
        assertTrue(remoteNewLinesAndSpaces(databaseMockObj.internalSql).contains(""""text"LIKE:p0"""))

        db.transaction { TestTable.find(Query(TestTable.nullableTest.isNull())) }
        assertTrue(remoteNewLinesAndSpaces(databaseMockObj.internalSql).contains(""""nullableTest"ISNULL"""))

        db.transaction { TestTable.find(Query(TestTable.nullableTest.isNotNull())) }
        assertTrue(remoteNewLinesAndSpaces(databaseMockObj.internalSql).contains(""""nullableTest"ISNOTNULL"""))

        db.transaction { TestTable.find(Query(not(TestTable.position eq 0))) }
        assertTrue(remoteNewLinesAndSpaces(databaseMockObj.internalSql).contains("""NOT("position"=:p0)"""))
    }

    @Test
    fun testJoinGeneratesQualifiedSql() {
        db.autocommit {
            (TestTable innerJoin TestOrders on (TestTable.id eq TestOrders.productId))
                .where(TestTable.position gtEq 1)
                .select(TestTable.text, TestOrders.orderId)
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""SELECT"products"."text","orders"."orderId""""), sql)
        assertTrue(
            sql.contains("""FROM"products"INNERJOIN"orders"ON"products"."id"="orders"."productId""""),
            sql,
        )
        assertTrue(sql.contains("""WHERE"products"."position">=:p0"""), sql)
        assertEquals(mapOf("p0" to 1), databaseMockObj.internalParams)
    }

    @Test
    fun testGroupByAndAggregateSql() {
        val total = TestTable.price.sum()
        db.autocommit {
            TestTable.query()
                .groupBy(TestTable.position)
                .having(total gt Value(100.0))
                .select(TestTable.position, count(), total)
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""SELECT"products"."position",COUNT(*),SUM("products"."price")"""), sql)
        assertTrue(sql.contains("""GROUPBY"products"."position""""), sql)
        assertTrue(sql.contains("""HAVINGSUM("products"."price")>:p0"""), sql)
        assertEquals(mapOf("p0" to 100.0), databaseMockObj.internalParams)
    }

    @Test
    fun testGroupedQueryOrdersByAggregateAndLimits() {
        // The top-N-by-aggregate shape: unexpressible before ORDER BY / LIMIT existed on Join.
        val total = TestTable.price.sum()
        db.autocommit {
            TestTable.query()
                .groupBy(TestTable.position)
                .orderBy { DESC(total) }
                .limit(10)
                .select(TestTable.position, total)
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""GROUPBY"products"."position""""), sql)
        assertTrue(sql.contains("""ORDERBYSUM("products"."price")DESC"""), sql)
        assertTrue(sql.contains("LIMIT10"), sql)
        // ORDER BY must follow GROUP BY/HAVING and precede LIMIT, or the statement is invalid SQL.
        assertTrue(sql.indexOf("GROUPBY") < sql.indexOf("ORDERBY"), sql)
        assertTrue(sql.indexOf("ORDERBY") < sql.indexOf("LIMIT"), sql)
    }

    @Test
    fun testJoinOrderingKeepsDeclarationOrderAndQualifiesColumns() {
        db.autocommit {
            (TestTable innerJoin TestOrders on (TestTable.id eq TestOrders.productId))
                .orderBy { DESC(TestTable.position); ASC(TestOrders.orderId) }
                .select(TestTable.text, TestOrders.orderId)
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(
            sql.contains("""ORDERBY"products"."position"DESC,"orders"."orderId"ASC"""),
            sql,
        )
    }

    @Test
    fun testJoinOrderingAccumulatesAcrossCalls() {
        db.autocommit {
            TestTable.query()
                .orderBy { DESC(TestTable.position) }
                .orderBy { ASC(TestTable.text) }
                .select(TestTable.text)
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""ORDERBY"products"."position"DESC,"products"."text"ASC"""), sql)
    }

    @Test
    fun testJoinPairFindPaginates() {
        // Ordering/limit live on the pair, so the entity-pair read keeps them.
        db.autocommit {
            (TestTable innerJoin TestOrders on (TestTable.id eq TestOrders.productId))
                .orderBy { ASC(TestTable.position) }
                .limit(5)
                .offset(10)
                .find()
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""ORDERBY"products"."position"ASC"""), sql)
        assertTrue(sql.contains("LIMIT5"), sql)
        assertTrue(sql.contains("OFFSET10"), sql)
    }

    @Test
    fun testLeftJoinPairFindPaginates() {
        db.autocommit {
            (TestTable leftJoin TestOrders on (TestTable.id eq TestOrders.productId))
                .orderBy { DESC(TestTable.position) }
                .limit(3)
                .find()
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""LEFTJOIN"""), sql)
        assertTrue(sql.contains("""ORDERBY"products"."position"DESC"""), sql)
        assertTrue(sql.contains("LIMIT3"), sql)
    }

    @Test
    fun testJoinOrderingSurvivesAdditionalJoin() {
        // A further join rebuilds the Join; ordering set before it must not be dropped.
        db.autocommit {
            TestTable.query()
                .orderBy { DESC(TestTable.position) }
                .limit(7)
                .innerJoin(TestOrders).on(TestTable.id eq TestOrders.productId)
                .select(TestTable.text)
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""ORDERBY"products"."position"DESC"""), sql)
        assertTrue(sql.contains("LIMIT7"), sql)
    }

    @Test
    fun testUnorderedJoinRendersNoOrderByOrLimit() {
        db.autocommit { TestTable.query().select(TestTable.text) }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertFalse(sql.contains("ORDERBY"), sql)
        assertFalse(sql.contains("LIMIT"), sql)
        assertFalse(sql.contains("OFFSET"), sql)
    }

    @Test
    fun testNegativeLimitAndOffsetAreRejected() {
        // toUInt() would wrap -1 into a huge LIMIT; fail fast instead, as QueryBuilder does.
        assertFailsWith<IllegalArgumentException> { TestTable.query().limit(-1) }
        assertFailsWith<IllegalArgumentException> { TestTable.query().offset(-1) }
    }

    @Test
    fun testFindOneRendersPredicateAndLimitsToOne() {
        db.autocommit { Coded.findOne { where { Coded.code eq "abc" } } }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""WHERE"code"=:p0"""), sql)
        assertTrue(sql.contains("LIMIT1"), sql)
        assertEquals(mapOf("p0" to "abc"), databaseMockObj.internalParams)
    }

    companion object {
        val databaseMockObj = DatabaseMock()

        // Same instance, viewed as Database<TestCatalog> (covariance: Database<Nothing>
        // <: Database<TestCatalog>) so transaction { } resolves the catalog tag.
        val db: Database<TestCatalog> = databaseMockObj

        // Same instance, viewed as SuspendDatabase<TestCatalog>, so suspendTransaction { } resolves the tag.
        val suspendDb: SuspendDatabase<TestCatalog> = databaseMockObj

        fun remoteNewLinesAndSpaces(value: String): String {
            return value.replace("\n", "").replace(" ", "")
        }
    }
}
