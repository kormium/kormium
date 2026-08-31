package io.github.kormium

/**
 * Which SQLite engine a driver is running on. An extension consults it to pick how to install
 * itself: the engines differ not in whether they support extensions but in *how* an extension
 * reaches them — loaded from a shared library at runtime, or registered before connections open.
 *
 * See [SqliteExtension] and ADR 0013.
 */
public enum class SqliteEngine {
    /** JVM, sqlite-jdbc (org.xerial). */
    Xerial,

    /** Kotlin/Native and iOS, the vendored SQLite amalgamation via cinterop. */
    Native,

    /** Android, androidx.sqlite with the bundled SQLite. */
    AndroidX,

    /** Node, the better-sqlite3 npm package. */
    BetterSqlite3,

    /** Browser, wa-sqlite (`createSqliteWasmDatabase` / `createSqliteJsDatabase`). */
    WaSqlite,

    /** Browser, `@sqlite.org/sqlite-wasm` (the Worker-hosted and pooled engines). */
    SqliteWasm,
}

/**
 * Thrown when an extension asks an engine for something it cannot do — today,
 * [SqliteConnectionScope.loadLibrary] on an engine with no runtime library loading.
 *
 * It surfaces while the database is being opened, not at the first query that would have used the
 * extension, so a missing capability fails at application startup where it is easy to see.
 */
public class SqliteExtensionUnsupportedException(
    public val extension: String,
    public val engine: SqliteEngine,
    message: String,
) : KormiumException(message)
