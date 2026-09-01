package io.github.kormium

/**
 * Registers extensions with androidx.sqlite's bundled SQLite, through a small JNI shim.
 *
 * androidx never hands out the `sqlite3*` handle, so per-connection loading is impossible here.
 * Its native library does export `sqlite3_auto_extension`, which needs only a function pointer and
 * applies to every connection opened afterwards — so registration is the only way in, and it
 * happens in [SqliteExtension.beforeOpen], before the driver builds its pool.
 *
 * The shim is extension-agnostic: an extension package ships its `.so` per ABI and names its entry
 * point, and writes no C of its own.
 */
internal object SqliteAndroidRegistrationScope : SqliteRegistrationScope {

    override val engine: SqliteEngine get() = SqliteEngine.AndroidX

    // Loaded on first use, not from an initialiser: every createSqliteDatabase touches this object
    // to run beforeOpen, and an app that declares no extensions must not be made to depend on a
    // JNI library it never needs — local unit tests and ABI-filtered builds would fail with
    // UnsatisfiedLinkError on a database that has nothing to do with extensions.
    private val shim by lazy { System.loadLibrary("kormium_sqlite_ext") }

    /** Returns null on success, or a message describing the failure. */
    private external fun nativeRegister(path: String, entryPoint: String): String?

    override fun registerLibrary(path: String, entryPoint: String) {
        shim
        val error = nativeRegister(path, entryPoint)
        if (error != null) {
            throw QueryException("failed to register the SQLite extension '$path' on Android: $error")
        }
    }
}
