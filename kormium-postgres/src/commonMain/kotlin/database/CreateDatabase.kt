package io.github.kormium.database

import io.github.kormium.KormiumBuilder
import io.github.kormium.KormiumConfig
import io.github.kormium.PostgresDriver
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * @param acquireTimeout how long a caller may wait for a pooled connection when all
 *   [poolSize] connections are busy before failing with
 *   [io.github.kormium.PoolExhaustedException] (on the JVM this is HikariCP's
 *   `connectionTimeout`, which has a 250 ms floor). A bounded wait turns a saturated pool
 *   into a clear, catchable error instead of an indefinite hang.
 */
public expect fun createDatabase(
    host: String,
    port: Int = 5432,
    database: String,
    user: String,
    password: String,
    poolSize: Int = 10,
    acquireTimeout: Duration = 30.seconds,
    config: KormiumConfig = KormiumConfig(),
): PostgresDriver

/**
 * Opens a PostgreSQL database with a configuration block: `createDatabase(host = …, …) {`
 * `config { … }; beforeStart { migrate(appMigrations) } }`. See [KormiumBuilder].
 */
public fun createDatabase(
    host: String,
    port: Int = 5432,
    database: String,
    user: String,
    password: String,
    poolSize: Int = 10,
    acquireTimeout: Duration = 30.seconds,
    block: KormiumBuilder.() -> Unit,
): PostgresDriver = KormiumBuilder().apply(block).finish {
    createDatabase(host, port, database, user, password, poolSize, acquireTimeout, it)
}
