@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.count
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.eq
import io.github.kormium.inList
import io.github.kormium.leftJoin
import io.github.kormium.query
import io.github.kormium.sum
import io.github.kormium.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * SQLite mirror of the Postgres `EdgeCaseTest`: the same edge/corner cases run against an
 * in-memory SQLite database so they execute on every target without Docker. Documents where
 * SQLite agrees with Postgres (savepoint rollback, nullable left-join, empty IN, boundary
 * ints, reserved-word columns, special characters round-tripping through parameterization).
 *
 * Purely additive; uses the public DSL only. Backend behavior differences are documented in
 * docs/backends.md ("Backend behavior matrix").
 */
class SqliteEdgeCaseTest {

    private val db: Database<EdgeCat> = createSqliteDatabase(":memory:")

    private fun freshSchema() = db.transaction {
        EdgeTbl.execSql(edgeTblDdl)
        EdgeKid.execSql(edgeKidDdl)
        ReservedTbl.execSql(reservedTblDdl)
    }

    @Test
    fun findByIdMissingReturnsNull() {
        freshSchema()
        assertNull(db.autocommit { EdgeTbl.findOne { where { EdgeTbl.id eq Uuid.random() } } })
    }

    @Test
    fun nullableColumnsRoundTripNull() {
        freshSchema()
        val id = Uuid.random()
        db.transaction { EdgeTbl.insert(EdgeR().apply { this.id = id; n = null; t = null; num = 1 }) }
        val row = db.autocommit { EdgeTbl.findOne { where { EdgeTbl.id eq id } } }!!
        assertNull(row.n)
        assertNull(row.t)
        assertEquals(1, row.num)
    }

    @Test
    fun leftJoinFindMissingRightIsNullPair() {
        freshSchema()
        val pid = Uuid.random()
        db.transaction { EdgeTbl.insert(EdgeR().apply { id = pid; num = 1 }) }
        val pairs: List<Pair<EdgeR, EdgeKidR?>> = db.autocommit {
            (EdgeTbl leftJoin EdgeKid on (EdgeTbl.id eq EdgeKid.parentId))
                .where(EdgeTbl.id eq pid)
                .find()
        }
        assertEquals(1, pairs.size)
        assertEquals(pid, pairs.single().first.id)
        assertNull(pairs.single().second, "an unmatched right side must be null")
    }

    @Test
    fun countOfEmptyIsZeroAndSumIsNull() {
        freshSchema()
        val c = count()
        val s = EdgeTbl.num.sum()
        val row = db.autocommit { EdgeTbl.query().where(EdgeTbl.num eq -888_888).select(c, s) }.single()
        assertEquals(0L, row[c])
        assertNull(row.getOrNull(s))
    }

    @Test
    fun emptyInListMatchesNothing() {
        freshSchema()
        assertTrue(db.autocommit { EdgeTbl.find(Query(EdgeTbl.id inList emptyList())) }.isEmpty())
    }

    @Test
    fun valueWithSpecialCharactersRoundTrips() {
        freshSchema()
        val id = Uuid.random()
        val tricky = "a'b\"c\\d\neé\t--; DROP TABLE edge; --"
        db.transaction { EdgeTbl.insert(EdgeR().apply { this.id = id; t = tricky; num = 1 }) }
        assertEquals(tricky, db.autocommit { EdgeTbl.findOne { where { EdgeTbl.id eq id } } }?.t)
    }

    @Test
    fun boundaryIntegerValuesRoundTrip() {
        freshSchema()
        val id = Uuid.random()
        db.transaction { EdgeTbl.insert(EdgeR().apply { this.id = id; n = Int.MIN_VALUE; num = Int.MAX_VALUE }) }
        val row = db.autocommit { EdgeTbl.findOne { where { EdgeTbl.id eq id } } }!!
        assertEquals(Int.MIN_VALUE, row.n)
        assertEquals(Int.MAX_VALUE, row.num)
    }

    @Test
    fun updateMatchingNoRowsIsNoOp() {
        freshSchema()
        db.transaction { EdgeTbl.update(EdgeR().apply { num = 5 }, Query(EdgeTbl.id eq Uuid.random())) }
    }

    @Test
    fun batchInsertEmptyListIsNoOp() {
        freshSchema()
        assertTrue(db.transaction { EdgeTbl.insertAll(emptyList<EdgeR>()) }.isEmpty())
    }

    @Test
    fun nestedSavepointsRollBackInnerWork() {
        freshSchema()
        val keep = Uuid.random()
        val mid = Uuid.random()
        val inner = Uuid.random()
        db.transaction {
            EdgeTbl.insert(EdgeR().apply { id = keep; num = 1 })
            runCatching {
                savepoint {
                    EdgeTbl.insert(EdgeR().apply { id = mid; num = 2 })
                    savepoint {
                        EdgeTbl.insert(EdgeR().apply { id = inner; num = 3 })
                        throw RuntimeException("boom")
                    }
                }
            }
        }
        assertEquals(keep, db.autocommit { EdgeTbl.findOne { where { EdgeTbl.id eq keep } } }?.id)
        assertNull(db.autocommit { EdgeTbl.findOne { where { EdgeTbl.id eq mid } } })
        assertNull(db.autocommit { EdgeTbl.findOne { where { EdgeTbl.id eq inner } } })
    }

    @Test
    fun savepointRequiresTransaction() {
        freshSchema()
        assertFailsWith<IllegalStateException> {
            db.autocommit { savepoint { EdgeTbl.findOne { where { EdgeTbl.id eq Uuid.random() } } } }
        }
    }

    @Test
    fun reservedWordColumnNameRoundTrips() {
        freshSchema()
        val id = Uuid.random()
        db.transaction { ReservedTbl.insert(ReservedR().apply { this.id = id; order = 7 }) }
        assertEquals(7, db.autocommit { ReservedTbl.findOne { where { ReservedTbl.id eq id } } }?.order)
    }
}

object EdgeCat : Catalog

class EdgeR : Entity() {
    var id by EdgeTbl.id
    var n by EdgeTbl.n
    var t by EdgeTbl.t
    var num by EdgeTbl.num
}

object EdgeTbl : Table<EdgeCat, EdgeR>("edge", ::EdgeR) {
    val id by Column.UUID().primaryKey()
    val n by Column.Int().nullable()
    val t by Column.Text().nullable()
    val num by Column.Int()

    init { id; n; t; num }
}

class EdgeKidR : Entity() {
    var id by EdgeKid.id
    var parentId by EdgeKid.parentId
    var label by EdgeKid.label
}

object EdgeKid : Table<EdgeCat, EdgeKidR>("edge_kid", ::EdgeKidR) {
    val id by Column.UUID().primaryKey()
    val parentId by Column.UUID()
    val label by Column.Text()

    init { id; parentId; label }
}

class ReservedR : Entity() {
    var id by ReservedTbl.id
    var order by ReservedTbl.order
}

object ReservedTbl : Table<EdgeCat, ReservedR>("reserved_tbl", ::ReservedR) {
    val id by Column.UUID().primaryKey()
    val order by Column.Int()

    init { id; order }
}

// Raw schema DDL (Kormium does not own schema management). SQLite type affinity.
private val edgeTblDdl = """CREATE TABLE IF NOT EXISTS "edge" ("id" TEXT NOT NULL, "n" INTEGER, "t" TEXT, "num" INTEGER NOT NULL, PRIMARY KEY ("id"))"""
private val edgeKidDdl = """CREATE TABLE IF NOT EXISTS "edge_kid" ("id" TEXT NOT NULL, "parentId" TEXT NOT NULL, "label" TEXT NOT NULL, PRIMARY KEY ("id"))"""
private val reservedTblDdl = """CREATE TABLE IF NOT EXISTS "reserved_tbl" ("id" TEXT NOT NULL, "order" INTEGER NOT NULL, PRIMARY KEY ("id"))"""
