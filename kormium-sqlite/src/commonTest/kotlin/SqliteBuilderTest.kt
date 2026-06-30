import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.github.kormium.BatchInsertMode
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.eq
import io.github.kormium.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** The createSqliteDatabase { } builder: config { } is applied and beforeStart { } runs before
 *  the database is returned (here, migrations create the table). JVM + native. */
class SqliteBuilderTest {

    @Test
    fun builderAppliesConfigAndRunsBeforeStart() {
        val db: Database<SqCatalog> = createSqliteDatabase {
            config { batchInsertMode = BatchInsertMode.UnionNulls }
            beforeStart { autocommit { executeUpdate(productsDdl) } }   // create the schema before the db is returned
        }

        db.use {
            // config { } was applied to the handle.
            assertEquals(BatchInsertMode.UnionNulls, db.config.batchInsertMode)

            // beforeStart ran the migration, so the table exists and is usable.
            val id = Uuid.random()
            db.transaction {
                Products.insert(Product().apply {
                    this.id = id
                    this.price = BigDecimal.fromInt(5)
                    this.qty = 1
                    this.displayName = "from-builder"
                    this.note = null
                    this.rank = null
                })
            }
            assertEquals("from-builder", db.autocommit { Products.findOne { where { Products.id eq id } } }?.displayName)
        }
    }
}
