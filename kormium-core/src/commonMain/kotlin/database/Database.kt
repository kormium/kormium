package io.github.kormium.database

import io.github.kormium.Catalog
import io.github.kormium.Dialect
import io.github.kormium.KormiumConfig
import io.github.kormium.SqlExecutor
import io.github.kormium.StandardDialect
import io.github.kormium.TransactionIsolation
import io.github.kormium.WriteListeners

/**
 * A database handle, tagged with the [Catalog] [G] it connects to. The tag is
 * phantom (it appears in no member), so a backend driver implements
 * `Database<Nothing>` and — by covariance — fits any `Database<G>`; a caller pins
 * the tag by assigning it to a `Database<MyCatalog>`. A [io.github.kormium.Table]
 * tagged with the same catalog can then be used against it via
 * [io.github.kormium.transaction] / [io.github.kormium.autocommit].
 *
 * SQL runs only through a pinned [SqlExecutor] inside a scope — the database handle is not
 * itself an [SqlExecutor]. Run one-off statements via `autocommit { execute(...) }` so every
 * write goes through a scope (and is therefore transactional and observable).
 */
public interface Database<out G : Catalog> : AutoCloseable {
    /** Per-database configuration; defaults to [KormiumConfig] defaults unless a backend overrides it. */
    public val config: KormiumConfig get() = KormiumConfig()

    /**
     * The SQL dialect this database renders to (placeholder style, identifier quoting, LIMIT/OFFSET,
     * upsert tail, …). Backends override it with their concrete dialect; the neutral
     * [StandardDialect] default keeps custom/test implementations compiling. Exposed so a query can
     * be rendered to its SQL without a connection — see [io.github.kormium.renderSql].
     */
    public val dialect: Dialect get() = StandardDialect

    /**
     * The write-notification registry for this database. The default [WriteListeners.Disabled]
     * means change observation (e.g. `kormium-observe`) does nothing; a backend opts in by
     * overriding this with a real [WriteListeners] instance.
     */
    public val writeListeners: WriteListeners get() = WriteListeners.Disabled

    /**
     * Pins one connection for the duration of [block]; the [SqlExecutor] passed to it
     * routes every statement to that connection. Wraps BEGIN/COMMIT/ROLLBACK when
     * [transactional] is true, otherwise runs in autocommit. When transactional, [isolation]
     * (if non-null) and [readOnly] are applied to the opened transaction; both are ignored in
     * autocommit. Backend-specific.
     */
    public fun <R> usePinned(
        transactional: Boolean,
        isolation: TransactionIsolation? = null,
        readOnly: Boolean = false,
        block: (SqlExecutor) -> R,
    ): R

    /**
     * Whether [close] has been called. Defaults to `false` for backends that do not track it.
     * Once `true`, [usePinned] (and any `transaction` / `autocommit`) throws
     * [io.github.kormium.DatabaseClosedException].
     */
    public val isClosed: Boolean get() = false

    /**
     * Closes the underlying connection(s); the database is unusable afterwards. The contract,
     * honored by all shipped backends:
     *
     *  - **Idempotent:** calling [close] more than once is safe; only the first call tears down.
     *  - **Use-after-close throws:** a [usePinned] (and therefore any `transaction` /
     *    `autocommit`) after [close] throws [io.github.kormium.DatabaseClosedException], not a
     *    backend-specific error.
     *  - **In-flight statements:** a statement already running when [close] is called is allowed
     *    to finish where the backend can wait for its connection (the native pools drain on
     *    close); a call that races [close] may instead throw `DatabaseClosedException`.
     */
    override fun close()
}
