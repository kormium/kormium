import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.LockWait
import io.github.kormium.BackendDatabase
import io.github.kormium.PostgresBackend
import io.github.kormium.RowLockingBackend
import io.github.kormium.Table
import io.github.kormium.database.Database
import io.github.kormium.eq
import io.github.kormium.forUpdate
import io.github.kormium.transaction

private object QueueCatalog : Catalog

private class JobRow : Entity() {
    var id by JobsTable.id
    var status by JobsTable.status
}

private object JobsTable : Table<QueueCatalog, JobRow>("jobs", ::JobRow) {
    val id by Column.Int()
    val status by Column.Text()
}

/**
 * Compile-time behaviour is the assertion here: this file passing the compiler pins that the
 * locking DSL is reachable through a Postgres-typed handle — i.e. that the `transaction` **member**
 * on [BackendDatabase] wins overload resolution against the core `Database<G>.transaction`
 * extension — and that a handle widened to the portable type keeps working for everything else.
 */
@Suppress("unused")
class RowLockingGatingTest {

    /**
     * The queue pattern from issue #162. Note the handle type: pinning the catalog on a
     * *Postgres* handle is what opens the DSL.
     *
     * A raw `PostgresDriver` (whose catalog tag is `Nothing`) will NOT do here: the member fixes
     * the catalog to the interface's own parameter instead of inferring it at the call site the
     * way the core extension does, so `JobsTable.find` would not resolve. Pinning the driver to a
     * catalog is the documented idiom anyway (see docs/quick-start.md).
     */
    private fun claimBatch(db: BackendDatabase<QueueCatalog, PostgresBackend>): List<JobRow> = db.transaction {
        JobsTable.find {
            where { JobsTable.status eq "ACTIVE" }
            limit = 10
            forUpdate(LockWait.SkipLocked)
        }
    }

    private fun lockOneRow(db: BackendDatabase<QueueCatalog, PostgresBackend>): JobRow? = db.transaction {
        JobsTable.findOne { where { JobsTable.id eq 1 }; forUpdate() }
    }

    /**
     * The point of tagging capabilities rather than engines: a helper can ask for "any backend that
     * can lock" and still use the locking DSL. This compiles only because [BackendDatabase]'s
     * backend parameter is covariant — and it accepts a MySQL driver just as well.
     */
    private fun claimOnAnyLockingBackend(db: BackendDatabase<QueueCatalog, RowLockingBackend>): List<JobRow> =
        db.transaction { JobsTable.find { limit = 1; forUpdate(LockWait.SkipLocked) } }

    /** The gated DSL must also be previewable through the handle's own renderSql. */
    private fun previewTheLockingQuery(db: BackendDatabase<QueueCatalog, PostgresBackend>): String =
        db.renderSql { JobsTable.find { forUpdate() } }.sql

    /** The same driver widened to the portable handle: everything but the locking call still works. */
    private fun portableHandleStaysPortable(db: Database<QueueCatalog>): List<JobRow> = db.transaction {
        // `forUpdate()` here does not resolve — that is the compile-time half of the guarantee.
        JobsTable.find { where { JobsTable.status eq "ACTIVE" } }
    }
}
