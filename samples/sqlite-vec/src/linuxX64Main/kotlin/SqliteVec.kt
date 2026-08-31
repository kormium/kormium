package io.github.kormium.samples.sqlitevec

import cvec.kormium_register_vec
import io.github.kormium.SqliteConnectionScope
import io.github.kormium.SqliteEngine
import io.github.kormium.SqliteExtension
import io.github.kormium.SqliteRegistrationScope
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * [sqlite-vec](https://github.com/asg017/sqlite-vec) packaged as a Kormium extension — the shape a
 * real third-party package would have.
 *
 * On Kotlin/Native the extension is linked statically, so there is nothing to load at runtime:
 * [beforeOpen] registers its entry point with `sqlite3_auto_extension` before the driver opens its
 * pool, and every connection opened afterwards has it. [install] then only proves it is really
 * there, which is also exactly what a package would do on an engine where the extension is
 * compiled into the binary.
 *
 * Registration is process-global: once any database declares this extension, every SQLite
 * connection opened later in the process has `vec0` available.
 */
public object SqliteVec : SqliteExtension {

    override val name: String = "sqlite-vec"

    // This sample only builds the Kotlin/Native flavour; a real package would list every engine
    // it ships a binary for.
    override val supportedEngines: Set<SqliteEngine> = setOf(SqliteEngine.Native)

    @OptIn(ExperimentalForeignApi::class)
    override fun beforeOpen(registration: SqliteRegistrationScope) {
        check(registration.engine == SqliteEngine.Native) { "this sample only builds the Kotlin/Native flavour" }
        // A repeat registration is a harmless no-op in SQLite, so this needs no guard of its own.
        check(kormium_register_vec() == 0) { "sqlite3_auto_extension(sqlite3_vec_init) failed" }
    }

    override fun install(connection: SqliteConnectionScope) {
        checkNotNull(connection.queryScalar("select vec_version()")) {
            "sqlite-vec is linked but did not register on this connection"
        }
    }
}
