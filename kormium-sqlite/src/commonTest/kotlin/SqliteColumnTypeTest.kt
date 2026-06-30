import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.TextColumnType
import io.github.kormium.convert
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private enum class Grade { A, B, C }

private class Item : Entity() {
    var id by Items.id
    var grade by Items.grade
    var hex by Items.hex
}

private object Items : Table<SqCatalog, Item>("items", ::Item) {
    val id by Column.UUID().primaryKey()
    val grade by Column.enum<Grade>()                                  // enum -> text
    val hex by Column.of(TextColumnType.convert<Int, String>(          // custom converter -> text
        toStored = { it.toString(16) },
        fromStored = { it.toInt(16) },
    ))

    init { id; grade; hex }
}

private val itemsDdl =
    """CREATE TABLE IF NOT EXISTS "items" ("id" TEXT NOT NULL, "grade" TEXT NOT NULL, "hex" TEXT NOT NULL, PRIMARY KEY ("id"))"""

/** End-to-end (JVM + Native, real SQLite) for the open column-type system: an enum column and a
 *  user-defined converter round-trip through insert/read, and an enum predicate filters correctly. */
class SqliteColumnTypeTest {

    @Test
    fun enumAndConverterRoundTrip() = runTest {
        val db: SuspendDatabase<SqCatalog> = createSqliteDatabase(":memory:")
        db.suspendTransaction { Items.execSql(itemsDdl) }
        val id = Uuid.random()

        db.suspendTransaction {
            Items.insert(Item().apply {
                this.id = id
                grade = Grade.B
                hex = 255
            })
        }

        val item = db.suspendAutocommit { Items.findOne { where { Items.id eq id } } }!!
        assertEquals(Grade.B, item.grade)
        assertEquals(255, item.hex)   // stored as "ff", read back as Int

        // Predicate on the enum column binds the stored name ("B"), so it matches.
        val bs = db.suspendAutocommit { Items.find { where { Items.grade eq Grade.B } } }
        assertEquals(listOf(id), bs.map { it.id })
    }
}
