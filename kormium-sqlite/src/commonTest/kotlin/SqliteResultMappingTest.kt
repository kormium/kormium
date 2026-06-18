import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Query
import io.github.kormium.ResultMappingException
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.eq
import io.github.kormium.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * End-to-end check that a database NULL in a column the entity declares non-null fails fast
 * during result mapping (not later, when the property is read). The DDL deliberately allows NULL
 * in `name` (no `NOT NULL`) while the entity declares it non-null, so a raw insert can plant the
 * bad row the mapper must reject.
 */
class SqliteResultMappingTest {

    private val db: Database<RmCat> = createSqliteDatabase(":memory:")

    @Test
    fun nonNullColumnReturningSqlNullThrowsOnHydration() {
        val id = Uuid.random()
        db.transaction {
            RmItems.execSql(rmDdlNameNullable)
            // Plant a row whose non-null `name` is actually NULL in the database.
            executeUpdate("INSERT INTO rm_items (id, note) VALUES (:id, :note)", mapOf("id" to id.toString(), "note" to "x"))
        }
        val ex = assertFailsWith<ResultMappingException> {
            db.autocommit { RmItems.findById(id) }
        }
        val msg = ex.message ?: ""
        assertTrue("rm_items" in msg && "name" in msg, "should name table+column: $msg")
    }

    @Test
    fun nullableColumnReturningSqlNullHydratesNormally() {
        val id = Uuid.random()
        db.transaction {
            RmItems.execSql(rmDdlNameNullable)
            executeUpdate("INSERT INTO rm_items (id, name) VALUES (:id, :name)", mapOf("id" to id.toString(), "name" to "ok"))
        }
        val row = db.autocommit { RmItems.findById(id) }
        assertEquals("ok", row?.name)
        assertNull(row?.note, "a nullable column with DB NULL must hydrate as null")
        db.transaction { RmItems.deleteWhere(Query(RmItems.id eq id)) }
    }
}

object RmCat : Catalog

class RmItem : Entity() {
    var id by RmItems.id
    var name by RmItems.name
    var note by RmItems.note
}

object RmItems : Table<RmCat, RmItem>("rm_items", ::RmItem) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()            // entity says non-null...
    val note by Column.Text().nullable()

    init { id; name; note }
}

// ...but the DDL allows NULL in "name", so a raw insert can plant a contract-violating row.
private val rmDdlNameNullable =
    """CREATE TABLE IF NOT EXISTS "rm_items" ("id" TEXT NOT NULL, "name" TEXT, "note" TEXT, PRIMARY KEY ("id"))"""
