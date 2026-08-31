package io.github.kormium.samples.sqliteuuid

import cuuid.kormium_register_uuid
import io.github.kormium.SqliteConnectionScope
import io.github.kormium.SqliteEngine
import io.github.kormium.SqliteExtension
import io.github.kormium.SqliteRegistrationScope
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * SQLite's own `ext/misc/uuid.c` packaged as a Kormium extension — a second, independent package,
 * used alongside [io.github.kormium.samples.sqlitevec.SqliteVec] to show that two extensions from
 * two packages coexist in one process and one query.
 */
public object SqliteUuid : SqliteExtension {

    override val name: String = "sqlite-uuid"

    override val supportedEngines: Set<SqliteEngine> = setOf(SqliteEngine.Native)

    @OptIn(ExperimentalForeignApi::class)
    override fun beforeOpen(registration: SqliteRegistrationScope) {
        check(registration.engine == SqliteEngine.Native) { "this sample only builds the Kotlin/Native flavour" }
        check(kormium_register_uuid() == 0) { "sqlite3_auto_extension(sqlite3_uuid_init) failed" }
    }

    override fun install(connection: SqliteConnectionScope) {
        checkNotNull(connection.queryScalar("select uuid()")) {
            "sqlite-uuid is linked but did not register on this connection"
        }
    }
}
