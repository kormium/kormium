package io.github.kormium

/**
 * What a locking read does when a row it wants is already locked by another transaction.
 *
 *  - [Wait] — block until the other transaction commits or rolls back (plain `FOR UPDATE`).
 *  - [NoWait] — fail immediately with [UnsupportedByDialectException]'s server-side counterpart
 *    (`NOWAIT`); use it for interactive edits, where freezing is worse than reporting a conflict.
 *  - [SkipLocked] — pretend the locked rows are not there and take the next ones (`SKIP LOCKED`);
 *    this is what makes a table usable as a work queue for several workers at once.
 */
public enum class LockWait { Wait, NoWait, SkipLocked }

/**
 * A row-level lock taken by a `SELECT`, held until the surrounding transaction ends.
 *
 * [share] picks the lock strength: `false` is an exclusive `FOR UPDATE` (nobody else may read it
 * for update or write it), `true` a shared `FOR SHARE` (others may still read it for share, but
 * not write it). [wait] picks what happens on contention — see [LockWait].
 *
 * Build one through the DSL ([forUpdate] / [forShare]) rather than directly: the DSL only resolves
 * on a backend tagged [RowLockingBackend], which is the compile-time half of the guarantee.
 */
public data class RowLock(val share: Boolean = false, val wait: LockWait = LockWait.Wait) {
    /** `FOR UPDATE SKIP LOCKED`, `FOR SHARE NOWAIT`, … — for error messages, not for SQL. */
    override fun toString(): String {
        val strength = if (share) "FOR SHARE" else "FOR UPDATE"
        return when (wait) {
            LockWait.Wait -> strength
            LockWait.NoWait -> "$strength NOWAIT"
            LockWait.SkipLocked -> "$strength SKIP LOCKED"
        }
    }
}

/**
 * Locks the selected rows exclusively until the transaction ends — `SELECT ... FOR UPDATE`.
 * Only resolves inside a scope whose backend is tagged [RowLockingBackend] (Postgres, MySQL),
 * and only inside `transaction { }`: in autocommit the lock is released by the very next
 * statement boundary, which makes it useless, so the query fails fast instead.
 *
 * ```kotlin
 * Jobs.find {
 *     where { Jobs.status eq JobStatus.ACTIVE }
 *     orderBy ASC Jobs.nextRunAt
 *     limit = 10
 *     forUpdate(LockWait.SkipLocked)   // hand each worker its own batch
 * }
 * ```
 */
@OptIn(KormiumDialectApi::class)
public fun SelectQueryBuilderOf<RowLockingBackend>.forUpdate(wait: LockWait = LockWait.Wait) {
    rowLock = RowLock(share = false, wait = wait)
}

/** The shared counterpart of [forUpdate] — `SELECT ... FOR SHARE`. */
@OptIn(KormiumDialectApi::class)
public fun SelectQueryBuilderOf<RowLockingBackend>.forShare(wait: LockWait = LockWait.Wait) {
    rowLock = RowLock(share = true, wait = wait)
}
