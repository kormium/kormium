package io.github.kormium

/**
 * How [insertAll] handles a batch whose entities do not all have the same set of assigned
 * fields (their "shape" — the ordered set of present fields).
 */
public enum class BatchInsertMode {
    /** All entities must share one shape; otherwise [insertAll] fails fast. Most explicit. */
    Strict,

    /**
     * Group entities by shape and run one batch INSERT per group. Preserves absent/default
     * semantics while staying convenient. With `returning = true`, results come back in the
     * original input order regardless of grouping. This is the default.
     */
    GroupByAssignedFields,

    /**
     * Use the union of all assigned fields across the batch and bind `NULL` for fields absent
     * on some entities, keeping the whole batch as one statement. Convenient but does NOT
     * preserve absent/default semantics: an omitted (default-bearing) column becomes an
     * explicit `NULL`. Opt-in.
     */
    UnionNulls,
}

/**
 * Per-database Kormium configuration, supplied to the `createDatabase` / `createSqliteDatabase` /
 * `createR2dbcDatabase` factories and carried on the resulting database handle.
 */
public data class KormiumConfig(
    /** Default [BatchInsertMode] for [insertAll] when no per-call override is given. */
    val batchInsertMode: BatchInsertMode = BatchInsertMode.GroupByAssignedFields,

    /**
     * Optional per-statement observation hook. When set, every statement run through a scope
     * (DSL operations, raw `execute`/`executeUpdate`, and migrations) is timed and reported as a
     * [QueryEvent] — across all backends, without wrapping repository methods. The event carries
     * the SQL template only, never parameter values. `null` (the default) disables observation
     * entirely: no executor wrapping, no overhead.
     */
    val queryObserver: QueryObserver? = null,
)
