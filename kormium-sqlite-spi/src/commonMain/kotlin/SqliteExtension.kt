package io.github.kormium

/**
 * A SQLite extension, packaged so that one object works on every engine.
 *
 * Kormium ships no extensions and curates no list of them: an extension package (`sqlite-vec` and
 * friends) is an ordinary dependency that implements this interface, and anyone can publish one.
 * Kormium provides this contract, the SQLite in the process, and the headers to compile against.
 *
 * Two phases, because extensions reach an engine in two different ways and a single hook cannot
 * express both:
 *
 * - [beforeOpen] — for extensions linked into the process, registered once via
 *   `sqlite3_auto_extension` before any connection exists (Kotlin/Native, iOS, Android).
 * - [install] / [suspendInstall] — for extensions loaded from a shared library, which happens per
 *   connection (JVM, Node) and must therefore also run on connections a pool recreates later.
 *
 * [install] and [suspendInstall] are the blocking and suspend halves of the same job: engines where
 * SQLite runs in the calling thread use the first, browser engines (async VFS or Worker) the
 * second. Implement whichever your target platforms need — [supportedEngines] is what says which
 * those are, and it is checked before anything is installed, so an extension used on an engine it
 * was never written for fails at `createSqliteDatabase` with a clear message instead of silently
 * doing nothing and surfacing as "no such module" on the first query.
 *
 * All three have defaults; where an extension is compiled into the engine they may all be no-ops
 * and the install phase only probes that it is really there.
 *
 * ```kotlin
 * public object SqliteVec : SqliteExtension {
 *     override val name: String = "sqlite-vec"
 *     override val supportedEngines: Set<SqliteEngine> = setOf(SqliteEngine.Native, SqliteEngine.Xerial)
 *
 *     override fun beforeOpen(registration: SqliteRegistrationScope) {
 *         when (registration.engine) {
 *             SqliteEngine.Native -> registerVecStatically()          // linked into the binary
 *             SqliteEngine.AndroidX -> registration.registerLibrary(vecSoPath(), "sqlite3_vec_init")
 *             else -> Unit                                            // loaded per connection
 *         }
 *     }
 *
 *     override fun install(connection: SqliteConnectionScope) {
 *         when (connection.engine) {
 *             SqliteEngine.Native -> check(connection.queryScalar("select vec_version()") != null)
 *             else -> connection.loadLibrary(vecLoadablePath())
 *         }
 *     }
 * }
 * ```
 */
public interface SqliteExtension {

    /** Human-readable name, used in error messages — e.g. `"sqlite-vec"`. */
    public val name: String

    /**
     * The engines this extension can install itself on. A driver checks it before touching any
     * connection and fails with [SqliteExtensionUnsupportedException] if its own engine is missing,
     * so "this package was never built for your platform" is a startup error with a name in it.
     *
     * There is deliberately no default: an extension that cannot say where it works would
     * otherwise silently do nothing on the platforms it forgot.
     */
    public val supportedEngines: Set<SqliteEngine>

    /**
     * Runs once per driver, before its first connection is opened. The place for extensions that
     * are taken process-globally rather than per connection — either linked into the binary and
     * registered by the package itself (Kotlin/Native), or handed to
     * [SqliteRegistrationScope.registerLibrary] (Android).
     *
     * Process-global means what it says: an extension registered here reaches every SQLite
     * connection opened afterwards in the process, including databases that never declared it.
     * Registering the same extension twice is a harmless no-op.
     */
    public fun beforeOpen(registration: SqliteRegistrationScope) {}

    /**
     * Runs on each connection the driver opens, including ones its pool recreates later, on the
     * engines that execute SQL in the calling thread (JVM, Kotlin/Native, iOS, Android, Node).
     *
     * Throwing fails the `createSqliteDatabase` call that triggered it, so a missing or broken
     * extension is reported at startup rather than at the first query that needed it.
     */
    public fun install(connection: SqliteConnectionScope) {}

    /**
     * The suspend counterpart of [install], called by the browser engines, whose SQLite lives
     * behind an async VFS or in a Worker and cannot be driven from a blocking call.
     */
    public suspend fun suspendInstall(connection: SuspendSqliteConnectionScope) {}
}
