package io.github.kormium.database

import io.github.kormium.KormiumConfig
import io.github.kormium.MySqlDriver
import io.github.kormium.mysql.MySqlNativeDriver

/**
 * A native MySQL/MariaDB [MySqlDriver] backed by a MySQL C client (MariaDB Connector/C) over a
 * fixed connection pool. The blocking path (usePinned) calls the client directly; the suspend path
 * (useConnection) offloads each blocking call to the IO dispatcher — there is no portable
 * non-blocking MySQL client API, so this is the same strategy the Postgres driver uses on Windows.
 */
public actual fun createDatabase(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    poolSize: Int,
    config: KormiumConfig,
): MySqlDriver = MySqlNativeDriver(host, port, database, user, password, poolSize, config)
