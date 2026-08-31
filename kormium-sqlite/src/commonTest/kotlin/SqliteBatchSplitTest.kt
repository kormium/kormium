@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.Query
import io.github.kormium.autocommit
import io.github.kormium.count
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.decimal.Decimal
import io.github.kormium.eq
import io.github.kormium.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Regression (#145): a batch insert used to render one multi-row `INSERT` per shape group and
 * never split it, so `rows * columns` bound parameters grew without bound until the statement
 * blew past the backend's ceiling — reported as an opaque "too many SQL variables" that says
 * nothing about batch size.
 *
 * SQLite's ceiling is `SQLITE_MAX_VARIABLE_NUMBER`, a **build** option rather than an engine
 * version: 32766 by default since 3.32, but 250000 in sqlite-jdbc's bundled library. So the two
 * targets this suite runs on exercise different halves of the fix — the native driver links a
 * system libsqlite3 and hits the real 32766 ceiling at this batch size, while the JVM driver has
 * headroom and instead proves the split loses no rows and preserves input order. Both matter:
 * a split that silently dropped or reordered rows would be worse than the failure it replaces.
 */
class SqliteBatchSplitTest {

    private val db: Database<SqCatalog> = createSqliteDatabase(":memory:")

    // Products has 6 columns and every row below assigns all of them, so this binds 36 000
    // parameters — past SqliteDialect.maxBoundParameters (32766), hence at least two statements.
    private val rows = 6_000

    private fun product(index: Int, tag: String) = Product().apply {
        id = Uuid.random()
        price = Decimal.of(index)
        qty = index
        displayName = tag
        note = null
        rank = null
    }

    @Test
    fun batchPastTheParameterCeilingInsertsEveryRow() {
        val tag = "split-${Uuid.random()}"

        db.transaction {
            Products.execSql(productsDdl)
            Products.insertAll(List(rows) { product(it, tag) })
        }

        assertEquals(rows.toLong(), db.autocommit { Products.count(Query(Products.displayName eq tag)) })
        db.transaction { Products.deleteWhere(Query(Products.displayName eq tag)) }
    }

    /**
     * The split is only correct if `RETURNING` rows scatter back into input order across
     * *several* statements, not merely within one — input order is what
     * [io.github.kormium.BatchInsertMode] already promises for shape grouping, and the split
     * must not quietly weaken it.
     */
    @Test
    fun splitBatchWithReturningKeepsInputOrder() {
        val tag = "split-returning-${Uuid.random()}"
        val input = List(rows) { product(it, tag) }

        val stored = db.transaction {
            Products.execSql(productsDdl)
            Products.insertAll(input, returning = true)
        }

        assertEquals(rows, stored.size)
        assertEquals(input.map { it.id }, stored.map { it.id })
        assertEquals(input.map { it.qty }, stored.map { it.qty })
        db.transaction { Products.deleteWhere(Query(Products.displayName eq tag)) }
    }
}
