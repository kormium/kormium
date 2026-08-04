import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.isSet
import io.github.kormium.unset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The entity field-storage contract, pinned at the accessor level.
 *
 * [Entity] distinguishes three states per column — absent (never assigned), explicit null,
 * and a concrete value — and the storage backing that is an implementation detail. These
 * tests exercise the observable behaviour only (property get/set, [isSet], [unset], and the
 * declaration-order column registry the storage is indexed by), so they hold across a change
 * of the underlying representation.
 *
 * Complements [TableTest] (which covers the same three states as rendered SQL) and
 * [ResultMappingTest] (which covers the non-null contract at the read boundary).
 */
class EntityFieldStorageTest {

    // ---- reading absent fields ----

    @Test
    fun nonNullFieldReadBeforeAssignmentThrowsNamingFieldAndTable() {
        val e = StoreRow()
        val ex = assertFailsWith<IllegalStateException> { e.name }
        val msg = ex.message ?: ""
        assertContains(msg, "name", message = "should name the entity field: $msg")
        assertContains(msg, "store", message = "should name the table: $msg")
    }

    @Test
    fun nullableFieldReadBeforeAssignmentIsNull() {
        // Absent and explicit-null are distinguishable via isSet, but both read back as null.
        val e = StoreRow()
        assertFalse(e.isSet(StoreTable.note))
        assertNull(e.note)
    }

    // ---- the three states ----

    @Test
    fun absentExplicitNullAndValueAreThreeDistinctStates() {
        val e = StoreRow()
        assertFalse(e.isSet(StoreTable.note))

        e.note = null
        assertTrue(e.isSet(StoreTable.note), "explicit null counts as set")
        assertNull(e.note)

        e.note = "n"
        assertTrue(e.isSet(StoreTable.note))
        assertEquals("n", e.note)

        e.unset(StoreTable.note)
        assertFalse(e.isSet(StoreTable.note), "unset returns the field to absent")
        assertNull(e.note)
    }

    @Test
    fun unsetNonNullFieldReturnsItToAbsentAndReadThrowsAgain() {
        val e = StoreRow()
        e.name = "Ada"
        assertTrue(e.isSet(StoreTable.name))
        assertEquals("Ada", e.name)

        e.unset(StoreTable.name)
        assertFalse(e.isSet(StoreTable.name))
        assertFailsWith<IllegalStateException> { e.name }
    }

    @Test
    fun unsettingAnAbsentFieldIsANoOp() {
        val e = StoreRow()
        e.unset(StoreTable.note)
        assertFalse(e.isSet(StoreTable.note))
    }

    // ---- assignment ----

    @Test
    fun reassignmentOverwritesRatherThanAccumulates() {
        val e = StoreRow()
        e.count = 1
        e.count = 2
        e.count = 3
        assertEquals(3, e.count)
        assertTrue(e.isSet(StoreTable.count))
    }

    @Test
    fun everyDeclaredColumnRoundTripsThroughItsAccessor() {
        val e = StoreRow()
        e.name = "Ada"
        e.count = 36
        e.active = true
        e.score = 4242L
        e.note = "n"
        e.renamed = "r"

        assertEquals("Ada", e.name)
        assertEquals(36, e.count)
        assertEquals(true, e.active)
        assertEquals(4242L, e.score)
        assertEquals("n", e.note)
        assertEquals("r", e.renamed)
    }

    @Test
    fun instancesDoNotShareStorage() {
        val a = StoreRow()
        val b = StoreRow()
        a.name = "Ada"
        b.name = "Grace"

        assertEquals("Ada", a.name)
        assertEquals("Grace", b.name)

        a.unset(StoreTable.name)
        assertFalse(a.isSet(StoreTable.name))
        assertTrue(b.isSet(StoreTable.name), "unsetting one entity must not affect another")
        assertEquals("Grace", b.name)
    }

    @Test
    fun entitiesBuiltByTheTableFactoryStartEmpty() {
        // hydrate() and the read path build entities through the table's factory, not `new`.
        val e = StoreTable.factory()
        assertFalse(e.isSet(StoreTable.name))
        assertFalse(e.isSet(StoreTable.note))
        assertFailsWith<IllegalStateException> { e.name }
    }

    // ---- the column registry the storage is keyed/indexed by ----

    @Test
    fun columnsAreRegisteredUnderFieldKeyInDeclarationOrder() {
        assertEquals(
            listOf("id", "name", "count", "active", "score", "note", "renamed"),
            StoreTable.getFieldDisplayNames().keys.toList(),
        )
    }

    @Test
    fun customSqlNameDoesNotLeakIntoFieldStorage() {
        // `renamed` is stored under its Kotlin property name, never under its SQL identifier.
        assertEquals("renamed_col", StoreTable.renamed.name)
        assertEquals("renamed", StoreTable.renamed.fieldKey)

        val e = StoreRow()
        e.renamed = "r"
        assertTrue(e.isSet(StoreTable.renamed))
        assertEquals("r", e.renamed)

        val keys = StoreTable.getFieldDisplayNames().keys
        assertTrue("renamed" in keys)
        assertFalse("renamed_col" in keys)
    }

    // ---- the read boundary ----

    @Test
    fun hydratedEntityExposesEveryLoadedValue() {
        val id = kotlin.uuid.Uuid.random()
        val row = StoreTable.hydrateNamed(
            mapOf(
                "id" to id, "name" to "Ada", "count" to 36, "active" to true,
                "score" to 4242L, "note" to null, "renamed" to "r",
            ),
        )
        assertEquals(id, row.id)
        assertEquals("Ada", row.name)
        assertEquals(36, row.count)
        assertEquals(true, row.active)
        assertEquals(4242L, row.score)
        assertNull(row.note)
        assertEquals("r", row.renamed)
    }

    @Test
    fun hydratedEntityReportsLoadedFieldsAsSet() {
        val row = StoreTable.hydrateNamed(
            mapOf(
                "id" to kotlin.uuid.Uuid.random(), "name" to "Ada", "count" to 1,
                "active" to false, "score" to 0L, "note" to null, "renamed" to "r",
            ),
        )
        // A row read from the database has every selected column set, including the NULL one.
        assertTrue(row.isSet(StoreTable.name))
        assertTrue(row.isSet(StoreTable.note), "a selected column that came back NULL is set, not absent")
        assertNotNull(row.id)
    }
}

object StoreCat : Catalog

object StoreTable : Table<StoreCat, StoreRow>("store", ::StoreRow) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val count by Column.Int()
    val active by Column.Boolean()
    val score by Column.Long()
    val note by Column.Text().nullable()
    val renamed by Column.Text(name = "renamed_col")

    init { id; name; count; active; score; note; renamed }
}

class StoreRow : Entity() {
    var id by StoreTable.id
    var name by StoreTable.name
    var count by StoreTable.count
    var active by StoreTable.active
    var score by StoreTable.score
    var note by StoreTable.note
    var renamed by StoreTable.renamed
}
