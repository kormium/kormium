import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.github.knyazevs.korm.Catalog
import io.github.knyazevs.korm.Column
import io.github.knyazevs.korm.Entity
import io.github.knyazevs.korm.ForeignKeyViolationException
import io.github.knyazevs.korm.Query
import io.github.knyazevs.korm.Table
import io.github.knyazevs.korm.UniqueViolationException
import io.github.knyazevs.korm.autocommit
import io.github.knyazevs.korm.count
import io.github.knyazevs.korm.createSqliteDatabase
import io.github.knyazevs.korm.database.Database
import io.github.knyazevs.korm.eq
import io.github.knyazevs.korm.gtEq
import io.github.knyazevs.korm.innerJoin
import io.github.knyazevs.korm.query
import io.github.knyazevs.korm.sum
import io.github.knyazevs.korm.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * End-to-end tests for the SQLite backend. They run on every target (JVM via
 * sqlite-jdbc, Native via the sqlite3 cinterop driver) against a shared in-memory
 * database, so no external server / Docker is needed. The schema is created through
 * raw DDL (Korm does not own schema management); the all-types round-trip is the key
 * check that SQLite's text storage and the verbatim temporal parsing line up.
 */
class SqliteIntegrationTest {

    // One shared in-memory database (shared-cache) for the whole class. Tables are
    // created with IF NOT EXISTS; every test uses fresh random ids.
    private val db: Database<SqCatalog> = createSqliteDatabase(":memory:")

    @Test
    fun testInsertFindUpdateDeleteRoundTrip() {
        val id = Uuid.random()
        db.transaction {
            Products.execSql(productsDdl)
            Products.insert(Product().apply {
                this.id = id
                this.price = BigDecimal.fromInt(100)
                this.qty = 5
                this.displayName = "widget"
                this.note = null
                this.rank = null
            })
        }

        val found = db.autocommit { Products.findById(id) }
        assertEquals(id, found?.id)
        assertEquals(5, found?.qty)
        assertEquals("widget", found?.displayName)
        assertNull(found?.note)
        // A nullable numeric column that is NULL must read back as null, not 0.
        assertNull(found?.rank)
        assertEquals(0, BigDecimal.fromInt(100).compareTo(found?.price!!))

        // Parameterized WHERE on a typed value.
        val byQty = db.autocommit { Products.find(Query(Products.qty eq 5)) }.filter { it.id == id }
        assertEquals(1, byQty.size)

        // Block DSL: where{} + orderBy + limit, and a null predicate.
        val viaDsl = db.autocommit {
            Products.find {
                where { Products.qty gtEq 5 }
                where { Products.note eq null }
                orderBy DESC Products.qty
                limit = 10
            }
        }.filter { it.id == id }
        assertEquals(1, viaDsl.size)
        assertEquals(1L, db.autocommit { Products.count { where { Products.id eq id } } })

        // Partial update via block DSL; returns the affected row count.
        val updated = db.transaction {
            Products.update(Product().apply { this.qty = 9 }) { where { Products.id eq id } }
        }
        assertEquals(1L, updated)
        assertEquals(9, db.autocommit { Products.findById(id) }?.qty)
        // No row matches → 0 affected.
        assertEquals(0L, db.transaction {
            Products.update(Product().apply { this.qty = 1 }) { where { Products.id eq Uuid.random() } }
        })

        val deleted = db.transaction { Products.deleteWhere { where { Products.id eq id } } }
        assertEquals(1L, deleted)
        assertNull(db.autocommit { Products.findById(id) })
    }

    @Test
    fun testUpsertInsertOrIgnoreAndBatchOrder() {
        db.transaction { Products.execSql(productsDdl) }
        val id = Uuid.random()

        // upsert as insert (no conflict).
        db.transaction {
            Products.upsert(
                entity = Product().apply { this.id = id; price = BigDecimal.fromInt(1); qty = 1; displayName = "a"; note = null; rank = null },
                onConflict = Products.id,
                update = Product().apply { qty = 2 },
            )
        }
        assertEquals(1, db.autocommit { Products.findById(id) }?.qty)

        // upsert again on the same id → DO UPDATE applies the patch.
        db.transaction {
            Products.upsert(
                entity = Product().apply { this.id = id; price = BigDecimal.fromInt(1); qty = 99; displayName = "a"; note = null; rank = null },
                onConflict = Products.id,
                update = Product().apply { qty = 2 },
            )
        }
        assertEquals(2, db.autocommit { Products.findById(id) }?.qty)

        // insertOrIgnore: existing id → 0 affected, new id → 1 affected.
        assertEquals(0L, db.transaction {
            Products.insertOrIgnore(
                Product().apply { this.id = id; price = BigDecimal.fromInt(1); qty = 5; displayName = "a"; note = null; rank = null },
                onConflict = Products.id,
            )
        })
        assertEquals(1L, db.transaction {
            Products.insertOrIgnore(
                Product().apply { this.id = Uuid.random(); price = BigDecimal.fromInt(1); qty = 5; displayName = "b"; note = null; rank = null },
                onConflict = Products.id,
            )
        })

        // GroupByAssignedFields batch with returning: two shapes, results keep input order.
        val idA = Uuid.random(); val idB = Uuid.random(); val idC = Uuid.random()
        val rows = db.transaction {
            Products.insertAll(
                listOf(
                    Product().apply { this.id = idA; price = BigDecimal.fromInt(1); qty = 1; displayName = "A"; note = null; rank = null },
                    Product().apply { this.id = idB; price = BigDecimal.fromInt(1); qty = 2; displayName = "B" }, // different shape (no note/rank)
                    Product().apply { this.id = idC; price = BigDecimal.fromInt(1); qty = 3; displayName = "C"; note = null; rank = null },
                ),
                returning = true,
            )
        }
        assertEquals(listOf("A", "B", "C"), rows.map { it.displayName })
    }

    /** A value containing a quote round-trips intact (proves parameterization). */
    @Test
    fun testValueWithQuoteRoundTrips() {
        val id = Uuid.random()
        val tricky = "O'Brien'; DROP TABLE products; --"
        db.transaction {
            Products.execSql(productsDdl)
            Products.insert(Product().apply {
                this.id = id; this.price = BigDecimal.fromInt(1); this.qty = 1
                this.displayName = tricky; this.note = null; this.rank = null
            })
        }
        assertEquals(tricky, db.autocommit { Products.findById(id) }?.displayName)
        db.transaction { Products.deleteWhere(Query(Products.id eq id)) }
    }

    /** Every supported column type round-trips through a generated table. */
    @Test
    fun testAllColumnTypesRoundTrip() {
        val id = Uuid.random()
        val instant = kotlinx.datetime.Instant.parse("2024-01-02T03:04:05Z")
        val json = kotlinx.serialization.json.JsonPrimitive("hi")
        val date = kotlinx.datetime.LocalDate.parse("2024-01-02")
        val time = kotlinx.datetime.LocalTime.parse("03:04:05")
        val dateTime = kotlinx.datetime.LocalDateTime.parse("2024-01-02T03:04:05")
        db.transaction {
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
        val row = db.autocommit { AllTypes.findById(id) }!!
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
        db.transaction { AllTypes.deleteWhere(Query(AllTypes.id eq id)) }
    }

    /** Batch insert + count() over a predicate. */
    @Test
    fun testBatchInsertAndCount() {
        val ids = List(3) { Uuid.random() }
        val tag = "batch-${Uuid.random()}"
        db.transaction {
            Products.execSql(productsDdl)
            Products.insertAll(ids.mapIndexed { i, id ->
                Product().apply {
                    this.id = id; this.price = BigDecimal.fromInt(i); this.qty = i
                    this.displayName = tag; this.note = null; this.rank = null
                }
            })
        }
        val count = db.autocommit { Products.count(Query(Products.displayName eq tag)) }
        assertEquals(3L, count)
        db.transaction { ids.forEach { Products.deleteWhere(Query(Products.id eq it)) } }
    }

    /**
     * Regression (#46): SUM over an Int column can exceed Int.MAX_VALUE. Column<Int>.sum()
     * returns Selectable<Long> and reads the aggregate as a Long, so the sum must not overflow.
     */
    @Test
    fun testSumOfIntColumnDoesNotOverflowInt() {
        val tag = "sum-${Uuid.random()}"
        db.transaction {
            Products.execSql(productsDdl)
            // Two rows of 2_000_000_000 sum to 4_000_000_000, which is past Int.MAX_VALUE.
            Products.insertAll(List(2) {
                Product().apply {
                    this.id = Uuid.random(); this.price = BigDecimal.fromInt(0); this.qty = 2_000_000_000
                    this.displayName = tag; this.note = null; this.rank = null
                }
            })
        }
        val total = Products.qty.sum() // Selectable<Long> via the integer-column overload
        val sum: Long = db.autocommit {
            Products.query().where(Products.displayName eq tag).select(total).single()[total]
        }
        assertEquals(4_000_000_000L, sum)
        db.transaction { Products.deleteWhere(Query(Products.displayName eq tag)) }
    }

    /** An exception out of a transaction block rolls back every statement in it. */
    @Test
    fun testTransactionRollsBackOnException() {
        val id = Uuid.random()
        db.transaction { Products.execSql(productsDdl) }
        assertFailsWith<RuntimeException> {
            db.transaction {
                Products.insert(Product().apply {
                    this.id = id; this.price = BigDecimal.fromInt(1); this.qty = 1
                    this.displayName = "rollback"; this.note = null; this.rank = null
                })
                throw RuntimeException("boom")
            }
        }
        assertNull(db.autocommit { Products.findById(id) })
    }

    /** A duplicate primary key surfaces as a typed UniqueViolationException. */
    @Test
    fun testUniqueViolationIsTyped() {
        val id = Uuid.random()
        db.transaction {
            Products.execSql(productsDdl)
            Products.insert(Product().apply {
                this.id = id; this.price = BigDecimal.fromInt(1); this.qty = 1
                this.displayName = "dup"; this.note = null; this.rank = null
            })
        }
        assertFailsWith<UniqueViolationException> {
            db.transaction {
                Products.insert(Product().apply {
                    this.id = id; this.price = BigDecimal.fromInt(2); this.qty = 2
                    this.displayName = "dup2"; this.note = null; this.rank = null
                })
            }
        }
        db.transaction { Products.deleteWhere(Query(Products.id eq id)) }
    }

    /**
     * A foreign-key violation surfaces as a typed ForeignKeyViolationException —
     * proving the `foreign_keys=ON` pragma is applied (SQLite defaults it OFF).
     * The FK constraint is created via raw DDL.
     */
    @Test
    fun testForeignKeyViolationIsTyped() {
        db.transaction {
            executeUpdate("CREATE TABLE IF NOT EXISTS fk_parent (id TEXT PRIMARY KEY)")
            executeUpdate(
                "CREATE TABLE IF NOT EXISTS fk_child (" +
                    "id TEXT PRIMARY KEY, parent_id TEXT REFERENCES fk_parent(id))"
            )
        }
        assertFailsWith<ForeignKeyViolationException> {
            db.transaction {
                executeUpdate(
                    "INSERT INTO fk_child (id, parent_id) VALUES (:id, :p)",
                    mapOf("id" to Uuid.random().toString(), "p" to Uuid.random().toString()),
                )
            }
        }
    }

    /** A join round-trips: ResultRow, projection, and entity pairs. */
    @Test
    fun testJoinRoundTrip() {
        val authorId = Uuid.random()
        val bookId = Uuid.random()
        db.transaction {
            Authors.execSql(authorsDdl)
            Books.execSql(booksDdl)
            Authors.insert(Author().apply { id = authorId; name = "Ada" })
            Books.insert(Book().apply { id = bookId; this.authorId = authorId; title = "Notes" })
        }

        val rows = db.autocommit {
            (Authors innerJoin Books on (Authors.id eq Books.authorId)).select()
        }
        assertEquals(1, rows.size)
        assertEquals("Ada", rows.single()[Authors.name])
        assertEquals("Notes", rows.single()[Books.title])

        val titles = db.autocommit {
            (Authors innerJoin Books on (Authors.id eq Books.authorId))
                .select(Authors.name, Books.title) { it[Authors.name] to it[Books.title] }
        }
        assertEquals(listOf("Ada" to "Notes"), titles)

        db.transaction { Books.deleteWhere(Query(Books.id eq bookId)); Authors.deleteWhere(Query(Authors.id eq authorId)) }
    }
}

object SqCatalog : Catalog

class Product : Entity() {
    var id by Products.id
    var price by Products.price
    var qty by Products.qty
    var displayName by Products.displayName
    var note by Products.note
    var rank by Products.rank
}

object Products : Table<SqCatalog, Product>("products", ::Product) {
    val id by Column.UUID().primaryKey()
    val price by Column.BigDecimal()
    val qty by Column.Int()
    val displayName by Column.Text()
    val note by Column.Text().nullable()
    val rank by Column.Int().nullable()

    init { id; price; qty; displayName; note; rank }
}

class Author : Entity() {
    var id by Authors.id
    var name by Authors.name
}

object Authors : Table<SqCatalog, Author>("authors", ::Author) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()

    init { id; name }
}

class Book : Entity() {
    var id by Books.id
    var authorId by Books.authorId
    var title by Books.title
}

object Books : Table<SqCatalog, Book>("books", ::Book) {
    val id by Column.UUID().primaryKey()
    val authorId by Column.UUID()
    val title by Column.Text()

    init { id; authorId; title }
}

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

object AllTypes : Table<SqCatalog, AllTypesEntity>("all_types", ::AllTypesEntity) {
    val id by Column.UUID().primaryKey()
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

// ---- Raw schema DDL for tests (Korm no longer owns createTable). SQLite type affinity:
// INTEGER / REAL / TEXT; non-native values (UUID, decimal, json, temporals) live as TEXT. ----
internal val productsDdl = """CREATE TABLE IF NOT EXISTS "products" ("id" TEXT NOT NULL, "price" TEXT NOT NULL, "qty" INTEGER NOT NULL, "displayName" TEXT NOT NULL, "note" TEXT, "rank" INTEGER, PRIMARY KEY ("id"))"""
internal val authorsDdl = """CREATE TABLE IF NOT EXISTS "authors" ("id" TEXT NOT NULL, "name" TEXT NOT NULL, PRIMARY KEY ("id"))"""
internal val booksDdl = """CREATE TABLE IF NOT EXISTS "books" ("id" TEXT NOT NULL, "authorId" TEXT NOT NULL, "title" TEXT NOT NULL, PRIMARY KEY ("id"))"""
internal val allTypesDdl = """CREATE TABLE IF NOT EXISTS "all_types" ("id" TEXT NOT NULL, "anInt" INTEGER NOT NULL, "aDouble" REAL NOT NULL, "aBool" INTEGER NOT NULL, "aText" TEXT NOT NULL, "aDecimal" TEXT NOT NULL, "anInstant" TEXT NOT NULL, "aJson" TEXT NOT NULL, "aLong" INTEGER NOT NULL, "aFloat" REAL NOT NULL, "aShort" INTEGER NOT NULL, "aDate" TEXT NOT NULL, "aTime" TEXT NOT NULL, "aDateTime" TEXT NOT NULL, PRIMARY KEY ("id"))"""
