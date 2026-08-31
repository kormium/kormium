package io.github.kormium

/**
 * One prepared step, in the order the caller declared it. Public because the engines that execute
 * these steps live in other modules; application code declares them through [SqliteOptionsBuilder]
 * rather than constructing them. Extensions, pragmas and raw
 * `onConnection` blocks share a single ordered list rather than three separate ones so that a
 * pragma written after an extension really runs after it — an extension may define the pragma.
 */
public sealed interface SqliteStep {
    public class Extension(public val extension: SqliteExtension) : SqliteStep
    public class Pragma(public val name: String, public val value: String) : SqliteStep
    public class OnConnection(public val block: SqliteConnectionScope.() -> Unit) : SqliteStep
}

/**
 * SQLite-specific driver options — the extensions to install, the pragmas to apply and any raw
 * per-connection blocks. Build one with the `sqlite { }` block of [createSqliteDatabase]; the
 * `vararg` constructor covers the programmatic case where only extensions are needed.
 */
public class SqliteOptions internal constructor(internal val steps: List<SqliteStep>) {

    /** Options carrying just [extensions], in the given order. `SqliteOptions()` is empty. */
    public constructor(vararg extensions: SqliteExtension) :
        this(extensions.map { SqliteStep.Extension(it) })

    /** True when nothing was declared — engines use it to keep their plain, hook-free path. */
    public val isEmpty: Boolean get() = steps.isEmpty()

    /**
     * Pragma names the caller set explicitly. Kormium skips its own default for each of these, the
     * same rule [sqlitePathParams] already applies to pragmas written into a `file:` URI.
     */
    public fun declaredPragmas(): Set<String> =
        steps.filterIsInstance<SqliteStep.Pragma>().mapTo(mutableSetOf()) { it.name.lowercase() }

    /**
     * Checks every extension against the engine and then runs its [SqliteExtension.beforeOpen] —
     * once per driver, before it opens its first connection. Registration is process-global (see
     * [SqliteExtension.beforeOpen]).
     *
     * The capability check comes first so that an extension built for other platforms fails here,
     * by name, rather than installing nothing and surfacing later as "no such module".
     */
    public fun beforeOpen(registration: SqliteRegistrationScope) {
        val engine = registration.engine
        steps.forEach { step ->
            if (step is SqliteStep.Extension) {
                val extension = step.extension
                if (engine !in extension.supportedEngines) {
                    throw SqliteExtensionUnsupportedException(
                        extension = extension.name,
                        engine = engine,
                        message = "the SQLite extension '${extension.name}' does not support the " +
                            "$engine engine (it supports ${extension.supportedEngines.sorted()})",
                    )
                }
                extension.beforeOpen(registration)
            }
        }
    }

    /**
     * Applies every step to a freshly opened connection, in declaration order. Called by each
     * driver after its own default pragmas, and on every connection its pool opens — including
     * ones recreated later, which is why this cannot live in a once-per-database hook.
     */
    public fun applyTo(scope: SqliteConnectionScope) {
        for (step in steps) {
            when (step) {
                is SqliteStep.Extension -> step.extension.install(scope)
                is SqliteStep.Pragma -> scope.exec("PRAGMA ${step.name}=${step.value}")
                is SqliteStep.OnConnection -> step.block(scope)
            }
        }
    }

    /**
     * The suspend counterpart of [applyTo], for the browser engines. A raw `onConnection { }` block
     * is written against the blocking scope, so it cannot run here — declaring one and then using a
     * browser engine is a configuration error, reported as such.
     */
    public suspend fun suspendApplyTo(scope: SuspendSqliteConnectionScope) {
        for (step in steps) {
            when (step) {
                is SqliteStep.Extension -> step.extension.suspendInstall(scope)
                is SqliteStep.Pragma -> scope.exec("PRAGMA ${step.name}=${step.value}")
                is SqliteStep.OnConnection -> throw SqliteExtensionUnsupportedException(
                    extension = "onConnection",
                    engine = scope.engine,
                    message = "onConnection { } runs against the blocking SqliteConnectionScope and " +
                        "cannot be used with the ${scope.engine} engine, whose SQLite is asynchronous",
                )
            }
        }
    }
}

/**
 * Builds [SqliteOptions] outside a `createSqliteDatabase { }` block — for the engines whose
 * factories take options directly (Node and the browser), and for options assembled once and
 * reused.
 *
 * ```kotlin
 * val db = createNodeSqliteDatabase("app.db", options = sqliteOptions {
 *     extension(SqliteVec)
 *     pragma("cache_size", "-64000")
 * })
 * ```
 */
public fun sqliteOptions(block: SqliteOptionsBuilder.() -> Unit): SqliteOptions {
    val steps = mutableListOf<SqliteStep>()
    SqliteOptionsBuilder(steps).apply(block)
    return SqliteOptions(steps.toList())
}

/**
 * Receiver of `createSqliteDatabase("app.db") { … }` — [KormiumBuilder] plus the SQLite-only
 * `sqlite { }` block.
 */
public class SqliteBuilder : KormiumBuilder() {
    private val steps = mutableListOf<SqliteStep>()

    /**
     * SQLite-specific configuration: extensions, pragmas and raw per-connection blocks. Everything
     * declared here is applied to every connection the driver opens.
     *
     * ```kotlin
     * val db = createSqliteDatabase("app.db", poolSize = 4) {
     *     sqlite {
     *         extension(SqliteVec)
     *         pragma("cache_size", "-64000")
     *     }
     *     beforeStart { migrate(appMigrations) }
     * }
     * ```
     */
    public fun sqlite(block: SqliteOptionsBuilder.() -> Unit) {
        SqliteOptionsBuilder(steps).apply(block)
    }

    /** The declared steps, in order. Called by the `createSqliteDatabase` factories. */
    public fun options(): SqliteOptions = SqliteOptions(steps.toList())
}

/** Receiver of the [SqliteBuilder.sqlite] block. */
@KormiumDsl
public class SqliteOptionsBuilder internal constructor(private val steps: MutableList<SqliteStep>) {

    /**
     * Installs [extension] on every connection this driver opens. The extension package decides
     * how — loading a shared library, or registering itself before connections exist. A missing or
     * broken extension fails the `createSqliteDatabase` call, not the first query that needed it.
     *
     * Note that on engines where an extension is registered statically the mechanism is
     * process-global: it reaches every SQLite connection opened afterwards in the process,
     * including databases that never declared it. See [SqliteExtension.beforeOpen].
     */
    public fun extension(extension: SqliteExtension) {
        steps += SqliteStep.Extension(extension)
    }

    /**
     * Applies `PRAGMA [name]=[value]` to every connection. A pragma set here wins over Kormium's
     * own default for it (`journal_mode`, `foreign_keys`, `busy_timeout`), exactly as one written
     * into a `file:` path does.
     *
     * Both are restricted to word characters (and `-`, for negative `cache_size`): they are
     * interpolated into the statement, and nothing legitimate here is more than a keyword or a
     * number.
     */
    public fun pragma(name: String, value: String) {
        require(name.isNotEmpty() && name.all { it == '_' || it.isLetterOrDigit() }) {
            "pragma name must be word characters, was '$name'"
        }
        require(value.isNotEmpty() && value.all { it == '_' || it == '-' || it.isLetterOrDigit() }) {
            "pragma value must be word characters or '-', was '$value'"
        }
        steps += SqliteStep.Pragma(name, value)
    }

    /**
     * Runs [block] on every connection the driver opens — the escape hatch for whatever the typed
     * options above do not cover.
     */
    @DelicateKormiumApi
    public fun onConnection(block: SqliteConnectionScope.() -> Unit) {
        steps += SqliteStep.OnConnection(block)
    }
}
