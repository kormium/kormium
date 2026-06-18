import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Cancellation semantics of the suspend path on a real (blocking) backend — SQLite over the
 * core offload runner (`runConnection`), which wraps rollback and release in `NonCancellable`.
 * The pool holds a single connection, so the follow-up query can only succeed if the cancelled
 * scope actually returned its connection: each test proves both rollback/no-op AND release.
 *
 * Uses real-time `runBlocking` + `withTimeout` (not virtual time) so cancellation races the
 * driver exactly as it would in production. See docs/transactions-and-migrations.md.
 */
class SqliteCancellationTest {

    // poolSize = 1: a leaked connection would make the verification query block forever (caught
    // by the withTimeout guard, turning a regression into a failure rather than a hang).
    private val db: SuspendDatabase<CxlCat> = createSqliteDatabase(":memory:", poolSize = 1)

    @Test
    fun cancelledSuspendTransactionRollsBackAndReleases() = runBlocking {
        db.suspendTransaction { CxlItems.execSql(cxlDdl) }
        val id = Uuid.random()

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(200) {
                db.suspendTransaction {
                    CxlItems.insert(CxlRow().apply { this.id = id; name = "doomed" })
                    delay(10_000) // cancelled by the timeout while the transaction is still open
                }
            }
        }

        // The insert must have been rolled back, and the single connection must be back in the pool.
        val found = withTimeout(5_000) { db.suspendAutocommit { CxlItems.findById(id) } }
        assertNull(found)
    }

    @Test
    fun cancelledSuspendAutocommitReleasesConnection() = runBlocking {
        db.suspendTransaction { CxlItems.execSql(cxlDdl) }

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(200) {
                db.suspendAutocommit {
                    CxlItems.find(Query(CxlItems.id eq Uuid.random()))
                    delay(10_000) // cancelled while the connection is pinned for autocommit
                }
            }
        }

        // No transaction to roll back; the contract is that the connection is released. With a
        // single connection, this query proves it (it would block forever if the conn leaked).
        withTimeout(5_000) { db.suspendAutocommit { CxlItems.findById(Uuid.random()) } }
        Unit
    }
}

object CxlCat : Catalog

class CxlRow : Entity() {
    var id by CxlItems.id
    var name by CxlItems.name
}

object CxlItems : Table<CxlCat, CxlRow>("cxl_items", ::CxlRow) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()

    init { id; name }
}

private val cxlDdl = """CREATE TABLE IF NOT EXISTS "cxl_items" ("id" TEXT NOT NULL, "name" TEXT NOT NULL, PRIMARY KEY ("id"))"""
