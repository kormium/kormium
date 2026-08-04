import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.ResultMappingException
import io.github.kormium.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Focused coverage of the non-null contract enforced by [Table.hydrate] at the read boundary. */
class ResultMappingTest {

    @Test
    fun nonNullColumnMappedToNullThrowsNamingTableAndColumn() {
        val ex = assertFailsWith<ResultMappingException> {
            MapTable.hydrateNamed(mapOf("id" to Uuid.random(), "name" to null, "note" to null))
        }
        val msg = ex.message ?: ""
        assertTrue("map_t" in msg, "message should name the table: $msg")
        assertTrue("name" in msg, "message should name the column: $msg")
    }

    @Test
    fun nullableColumnMappedToNullHydratesNormally() {
        val row = MapTable.hydrateNamed(mapOf("id" to Uuid.random(), "name" to "ok", "note" to null))
        assertEquals("ok", row.name)
        assertNull(row.note)
    }

    @Test
    fun fullyPopulatedRowHydrates() {
        val id = Uuid.random()
        val row = MapTable.hydrateNamed(mapOf("id" to id, "name" to "ok", "note" to "n"))
        assertEquals(id, row.id)
        assertEquals("ok", row.name)
        assertEquals("n", row.note)
    }
}

object MapCat : Catalog

class MapRow : Entity() {
    var id by MapTable.id
    var name by MapTable.name
    var note by MapTable.note
}

object MapTable : Table<MapCat, MapRow>("map_t", ::MapRow) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()            // non-null
    val note by Column.Text().nullable() // nullable

    init { id; name; note }
}
