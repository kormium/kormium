import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.uuid.Uuid
import io.github.knyazevs.korm.*
import io.github.knyazevs.korm.database.Database
import io.github.knyazevs.korm.database.SuspendDatabase
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
    val price by Column.BigDecimal()
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
                createdAt = kotlinx.datetime.Clock.System.now()
            })
        }
        assertTrue(
            databaseMockObj.internalSql.contains("\"created_at\""),
            "expected custom SQL name in: ${databaseMockObj.internalSql}",
        )
    }

    @Test
    fun testFindBlockDslMatchesQuery() {
        val price = BigDecimal.fromInt(100)
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
        assertEquals(mapOf("p0" to price.toString(), "p1" to 1), blockParams)
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
                id = Uuid.random(); price = BigDecimal.fromInt(1); position = 1; text = "x"
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
                        TestEntity().apply { id = Uuid.random(); price = BigDecimal.fromInt(1); position = 1; text = "a" },
                        TestEntity().apply { id = Uuid.random(); price = BigDecimal.fromInt(1); position = 2; text = "b"; nullableTest = "x" },
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
                    TestEntity().apply { id = Uuid.random(); price = BigDecimal.fromInt(1); position = 1; text = "a" },
                    TestEntity().apply { id = Uuid.random(); price = BigDecimal.fromInt(1); position = 2; text = "b"; nullableTest = "x" },
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
                entity = TestEntity().apply { id = Uuid.random(); price = BigDecimal.fromInt(1); position = 1; text = "a" },
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
                TestEntity().apply { id = Uuid.random(); price = BigDecimal.fromInt(1); position = 1; text = "a" },
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
                    entity = TestEntity().apply { id = Uuid.random(); price = BigDecimal.fromInt(1); position = 1; text = "a" },
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
                    TestEntity().apply { id = Uuid.random(); price = BigDecimal.fromInt(1); position = 1; text = "a" },
                    onConflict = emptyList(),
                )
            }
        }
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
        val price = BigDecimal.fromInt(100)
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
                "p1" to price.toString(),
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
        db.transaction {
            TestTable.insert(
                TestEntity().apply {
                    this.id = Uuid.random()
                    this.price = BigDecimal.fromInt(1)
                    this.position = 1
                    this.text = "x"
                    this.nullableTest = null
                },
                returning = true,
            )
        }
        assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
    }

    @Test
    fun testUpdate() {
        val uuid = Uuid.random()
        val price = BigDecimal.fromInt(100)
        val position = 1
        val text = "hello world"
        val expectedResult = """
            UPDATE "products"
            SET "id"=:p0, "price"=:p1, "position"=:p2, "text"=:p3, "nullableTest"=:p4
            WHERE "id" = :p5
        """
        db.transaction {
            TestTable.update(Query(TestTable.id eq uuid), TestEntity().apply {
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
                "p1" to price.toString(),
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
            TestTable.update(
                Query(TestTable.id eq uuid),
                TestEntity().apply { this.nullableTest = null },
            )
        }
        assertEquals(remoteNewLinesAndSpaces(expectedResult), remoteNewLinesAndSpaces(databaseMockObj.internalSql))
        assertEquals(mapOf("p0" to null, "p1" to uuid.toString()), databaseMockObj.internalParams)
    }

    @Test
    fun testSelect() {
        val price = BigDecimal.fromInt(100)
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
        assertEquals(mapOf("p0" to price.toString()), databaseMockObj.internalParams)
    }

    @Test
    fun testFindById() {
        val uuid = Uuid.random()
        val expectedResult = """SELECT "id", "price", "position", "text", "nullableTest" FROM "products" WHERE "id" = :p0"""
        db.transaction { TestTable.findById(uuid) }
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
    fun testUpdateWithNoNonNullFieldsFails() {
        assertFailsWith<IllegalArgumentException> {
            db.transaction { TestTable.update(Query(TestTable.id eq Uuid.random()), TestEntity()) }
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
                .having(total gt Value(BigDecimal.fromInt(100)))
                .select(TestTable.position, count(), total)
        }
        val sql = remoteNewLinesAndSpaces(databaseMockObj.internalSql)
        assertTrue(sql.contains("""SELECT"products"."position",COUNT(*),SUM("products"."price")"""), sql)
        assertTrue(sql.contains("""GROUPBY"products"."position""""), sql)
        assertTrue(sql.contains("""HAVINGSUM("products"."price")>:p0"""), sql)
        assertEquals(mapOf("p0" to BigDecimal.fromInt(100).toString()), databaseMockObj.internalParams)
    }

    @Test
    fun testFindByIdUsesMarkedPrimaryKeyColumn() {
        db.autocommit { Coded.findById("abc") }
        assertTrue(remoteNewLinesAndSpaces(databaseMockObj.internalSql).contains("""WHERE"code"=:p0"""))
        assertEquals(mapOf("p0" to "abc"), databaseMockObj.internalParams)
    }

    @Test
    fun testFindByIdFailsForCompositeKey() {
        assertFailsWith<IllegalStateException> {
            db.autocommit { CompositeKey.findById(Uuid.random()) }
        }
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
