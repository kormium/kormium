package io.github.kormium

/**
 * Registration scope for the Kotlin/Native driver.
 *
 * Native extension packages link their own static library and call `sqlite3_auto_extension`
 * themselves from [SqliteExtension.beforeOpen] — Kormium has nothing to register on their behalf,
 * because there is no shared library to point at. So [registerLibrary] is unavailable here even
 * though the *engine* does support process-global registration.
 */
internal object SqliteNativeRegistrationScope : SqliteRegistrationScope {

    override val engine: SqliteEngine get() = SqliteEngine.Native

    override fun registerLibrary(path: String, entryPoint: String): Nothing =
        throw SqliteExtensionUnsupportedException(
            extension = path,
            engine = SqliteEngine.Native,
            message = "a Kotlin/Native extension package links its own static library and calls " +
                "sqlite3_auto_extension itself; there is no shared library for Kormium to register. " +
                "Use loadLibrary from install() to load one at runtime instead.",
        )
}
