package io.github.kormium.database

import io.github.kormium.KormiumConfig

public actual fun createDatabase(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    poolSize: Int,
    config: KormiumConfig,
): io.github.kormium.PostgresDriver = io.github.kormium.postgres.FPostgresDriver(host, port, database, user, password, poolSize, config)
