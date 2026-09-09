import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.LockWait
import io.github.kormium.MySqlDatabase
import io.github.kormium.Table
import io.github.kormium.database.Database
import io.github.kormium.eq
import io.github.kormium.forUpdate
import io.github.kormium.transaction

private object MyGateCatalog : Catalog

private class MyGateRow : Entity() {
    var id by MyGateJobs.id
    var status by MyGateJobs.status
}

private object MyGateJobs : Table<MyGateCatalog, MyGateRow>("jobs", ::MyGateRow) {
    val id by Column.Int()
    val status by Column.Text()
}

/**
 * Compiling is the assertion: the MySQL counterpart of `RowLockingGatingTest` in kormium-postgres.
 * It pins that the `transaction` member on [MySqlDatabase] shadows the core extension, so the
 * locking DSL is reachable on a MySQL-typed handle and not on a portable one.
 */
@Suppress("unused")
class MySqlRowLockingGatingTest {

    private fun claimBatch(db: MySqlDatabase<MyGateCatalog>): List<MyGateRow> = db.transaction {
        MyGateJobs.find {
            where { MyGateJobs.status eq "ACTIVE" }
            limit = 10
            forUpdate(LockWait.SkipLocked)
        }
    }

    /** Widened to the portable handle: `forUpdate()` here would not resolve. */
    private fun portableHandleStaysPortable(db: Database<MyGateCatalog>): List<MyGateRow> = db.transaction {
        MyGateJobs.find { where { MyGateJobs.status eq "ACTIVE" } }
    }
}
