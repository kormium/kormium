package io.github.kormium

/**
 * Everything an extension is allowed to do to a SQLite connection while it is being prepared.
 *
 * Deliberately small and backend-agnostic: the concrete connection type differs per engine
 * (`java.sql.Connection`, a `CPointer<sqlite3>`, a better-sqlite3 `Database`), and none of them
 * belongs in a contract that third-party packages compile against.
 *
 * This is the **blocking** form, used by the engines where SQLite runs in the calling thread: JVM,
 * Kotlin/Native, iOS, Android and Node. The browser engines cannot offer it — their SQLite is
 * behind an async VFS or in a Worker — and use [SuspendSqliteConnectionScope] instead. Kormium
 * splits blocking and suspend surfaces this way throughout (`Database`/`SuspendDatabase`,
 * `Scope`/`SuspendScope`, `SqlExecutor`/`SuspendSqlExecutor`); the SPI follows the same shape.
 *
 * The `@KormiumDsl` marker keeps a nested `onConnection { }` body from implicitly reaching the
 * enclosing builder's members (e.g. calling `extension(...)` from inside a step that is itself
 * being executed).
 */
@KormiumDsl
public interface SqliteConnectionScope {

    /** The engine this connection belongs to, so an extension can choose its strategy. */
    public val engine: SqliteEngine

    /**
     * Loads a SQLite extension from the shared library at [path], calling [entryPoint] (or
     * SQLite's default entry point when `null`).
     *
     * Only the C loading API is enabled, and only for the duration of this call: the
     * `load_extension()` SQL function stays off, so application SQL cannot load arbitrary code.
     *
     * @throws SqliteExtensionUnsupportedException on an engine without runtime library loading.
     */
    public fun loadLibrary(path: String, entryPoint: String? = null)

    /** Runs a statement on this connection (a `PRAGMA`, an extension's own configuration call). */
    public fun exec(sql: String)

    /**
     * Runs [sql] and returns the first column of the first row as text, or `null` if the statement
     * produced no rows or failed. Intended for capability probes such as
     * `queryScalar("select vec_version()")` on an engine where the extension is compiled in rather
     * than loaded.
     */
    public fun queryScalar(sql: String): String?
}

/**
 * The suspend counterpart of [SqliteConnectionScope], for engines that cannot answer synchronously.
 *
 * In the browser SQLite is either behind an asynchronous VFS (wa-sqlite over IndexedDB, via
 * Asyncify) or in a Worker reached by `postMessage`. In both cases the answer arrives *after* a
 * blocking call would have had to return, so those engines implement this scope and call
 * [SqliteExtension.suspendInstall].
 */
@KormiumDsl
public interface SuspendSqliteConnectionScope {

    /** The engine this connection belongs to, so an extension can choose its strategy. */
    public val engine: SqliteEngine

    /** See [SqliteConnectionScope.loadLibrary]. */
    public suspend fun loadLibrary(path: String, entryPoint: String? = null)

    /** See [SqliteConnectionScope.exec]. */
    public suspend fun exec(sql: String)

    /** See [SqliteConnectionScope.queryScalar]. */
    public suspend fun queryScalar(sql: String): String?
}

/**
 * What an extension may ask of an engine *before* any connection exists — the receiver of
 * [SqliteExtension.beforeOpen], mirroring [SqliteConnectionScope] for the registration phase.
 *
 * It exists because some engines can only take an extension process-globally
 * (`sqlite3_auto_extension`), which by definition has to happen before the pool opens: on Android
 * the driver never hands out the `sqlite3*` handle, so per-connection loading is impossible and
 * this is the only way in. An extension that links itself statically (Kotlin/Native) does its own
 * registration here and ignores the scope.
 */
@KormiumDsl
public interface SqliteRegistrationScope {

    /** The engine being opened, so an extension can choose its strategy. */
    public val engine: SqliteEngine

    /**
     * Registers the extension at [path] with [entryPoint] for **every** SQLite connection this
     * process opens from now on, including ones outside this database.
     *
     * Unlike [SqliteConnectionScope.loadLibrary] this is not scoped to one connection, and it
     * cannot be undone. Engines that load per connection do not implement it.
     *
     * @throws SqliteExtensionUnsupportedException on an engine with no process-global registration.
     */
    public fun registerLibrary(path: String, entryPoint: String)
}

/**
 * The registration scope for engines that take extensions **per connection** rather than
 * process-globally — JVM, Node and the browser. [SqliteRegistrationScope.registerLibrary] is not
 * available there; those extensions do their work in [SqliteExtension.install] instead.
 */
public fun perConnectionRegistration(engine: SqliteEngine): SqliteRegistrationScope =
    object : SqliteRegistrationScope {
        override val engine: SqliteEngine get() = engine

        override fun registerLibrary(path: String, entryPoint: String): Nothing =
            throw SqliteExtensionUnsupportedException(
                extension = path,
                engine = engine,
                message = "the $engine engine loads extensions per connection, not process-wide: " +
                    "call loadLibrary from install() instead of registerLibrary from beforeOpen()",
            )
    }
