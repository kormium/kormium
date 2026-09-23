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
 * How strong a lock a locking read takes. The two portable strengths are [Update] and [Share];
 * the other two are PostgreSQL-only refinements that take weaker locks, so they conflict with
 * fewer concurrent statements.
 *
 * Kotlin enums are exhaustive in a `when`, so every value is declared up front even though two of
 * them render on one backend: adding a value later would break every dialect that switches on it.
 */
public enum class LockStrength {
    /** `FOR UPDATE` — exclusive; blocks anyone else reading it for update or writing it. */
    Update,

    /**
     * `FOR NO KEY UPDATE` — like [Update] but does not block a concurrent `FOR KEY SHARE`, so a
     * child row's foreign-key check can still pass. **PostgreSQL only.**
     */
    NoKeyUpdate,

    /** `FOR SHARE` — others may still read it for share, but not write it. */
    Share,

    /**
     * `FOR KEY SHARE` — the weakest: blocks only changes to the row's key columns (and a
     * `FOR UPDATE`). What a foreign-key check takes. **PostgreSQL only.**
     */
    KeyShare,
}

/**
 * A row-level lock taken by a `SELECT`, held until the surrounding transaction ends.
 *
 * [strength] picks how much the lock excludes (see [LockStrength]); [wait] picks what happens on
 * contention (see [LockWait]).
 *
 * Build one through the DSL ([forUpdate] / [forShare], plus a dialect module's own entries for the
 * PostgreSQL-only strengths) rather than directly: the DSL only resolves on a backend tagged
 * [RowLockingBackend], which is the compile-time half of the guarantee. A [RowLock] put on a
 * [Query] value by hand skips that half — it is then caught when the statement renders, either by
 * a dialect that cannot lock ([UnsupportedByDialectException]) or, for a statement with no place
 * to put a lock, by [Query.toWhereSql].
 */
public data class RowLock(
    val strength: LockStrength = LockStrength.Update,
    val wait: LockWait = LockWait.Wait,
) {
    /** `FOR UPDATE SKIP LOCKED`, `FOR NO KEY UPDATE`, … — for error messages, not for SQL. */
    override fun toString(): String {
        val clause = when (strength) {
            LockStrength.Update -> "FOR UPDATE"
            LockStrength.NoKeyUpdate -> "FOR NO KEY UPDATE"
            LockStrength.Share -> "FOR SHARE"
            LockStrength.KeyShare -> "FOR KEY SHARE"
        }
        return when (wait) {
            LockWait.Wait -> clause
            LockWait.NoWait -> "$clause NOWAIT"
            LockWait.SkipLocked -> "$clause SKIP LOCKED"
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
    rowLock = RowLock(strength = LockStrength.Update, wait = wait)
}

/** The shared counterpart of [forUpdate] — `SELECT ... FOR SHARE`. */
@OptIn(KormiumDialectApi::class)
public fun SelectQueryBuilderOf<RowLockingBackend>.forShare(wait: LockWait = LockWait.Wait) {
    rowLock = RowLock(strength = LockStrength.Share, wait = wait)
}
