package io.github.kormium.database

import io.github.kormium.KormiumBuilder
import io.github.kormium.KormiumConfig
import io.github.kormium.MySqlDriver

public expect fun createDatabase(
    host: String,
    port: Int = 3306,
    database: String,
    user: String,
    password: String,
    poolSize: Int = 10,
    config: KormiumConfig = KormiumConfig(),
): MySqlDriver

/**
 * Opens a MySQL/MariaDB database with a configuration block: `createDatabase(host = …, …) {`
 * `config { … }; beforeStart { migrate(appMigrations) } }`. See [KormiumBuilder].
 */
public fun createDatabase(
    host: String,
    port: Int = 3306,
    database: String,
    user: String,
    password: String,
    poolSize: Int = 10,
    block: KormiumBuilder.() -> Unit,
): MySqlDriver = KormiumBuilder().apply(block).finish {
    createDatabase(host, port, database, user, password, poolSize, it)
}
