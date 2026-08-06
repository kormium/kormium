import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.isSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One entity type bound to columns of two different tables.
 *
 * The type system permits this: `TwoTableRow`'s properties delegate to `LeftT.left` and
 * `RightT.right`, and both columns are `Column<*, *, TwoTableRow>`. Field storage is keyed by
 * the Kotlin property name, so the two never collide.
 *
 * Pinned because it constrains how field storage may be represented: any scheme that indexes
 * storage by a per-table column position would alias these two properties onto one slot.
 */
class SharedEntityTypeTest {

    @Test
    fun propertiesBackedByDifferentTablesDoNotAlias() {
        val e = TwoTableRow()
        e.left = 1
        e.right = 2

        assertEquals(1, e.left)
        assertEquals(2, e.right)
    }

    @Test
    fun presenceIsTrackedPerPropertyNotPerPosition() {
        val e = TwoTableRow()
        assertFalse(e.isSet(LeftT.left))
        assertFalse(e.isSet(RightT.right))

        // Both columns are the first (and only) column of their own table.
        assertEquals(0, LeftT.getFieldDisplayNames().keys.indexOf("left"))
        assertEquals(0, RightT.getFieldDisplayNames().keys.indexOf("right"))

        e.left = 1
        assertTrue(e.isSet(LeftT.left))
        assertFalse(e.isSet(RightT.right), "setting a column of one table must not mark the other's")
    }

    @Test
    fun eachTableRendersOnlyItsOwnColumns() {
        val e = TwoTableRow()
        e.left = 1
        e.right = 2

        // Each table writes only the fields backed by its own columns.
        val (leftSql, leftParams) = LeftT.insertSql(e, io.github.kormium.StandardDialect, io.github.kormium.StandardTypeMapper, false)
        val (rightSql, rightParams) = RightT.insertSql(e, io.github.kormium.StandardDialect, io.github.kormium.StandardTypeMapper, false)

        assertTrue("left" in leftSql, leftSql)
        assertFalse("right" in leftSql, leftSql)
        assertEquals(listOf<Any?>(1), leftParams.values.toList())

        assertTrue("right" in rightSql, rightSql)
        assertFalse("left" in rightSql, rightSql)
        assertEquals(listOf<Any?>(2), rightParams.values.toList())
    }
}

object TwoTableCat : Catalog

object LeftT : Table<TwoTableCat, TwoTableRow>("left_t", ::TwoTableRow) {
    val left by Column.Int()

    init { left }
}

object RightT : Table<TwoTableCat, TwoTableRow>("right_t", ::TwoTableRow) {
    val right by Column.Int()

    init { right }
}

class TwoTableRow : Entity() {
    var left by LeftT.left
    var right by RightT.right
}
