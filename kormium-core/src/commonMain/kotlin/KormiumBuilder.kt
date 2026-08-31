package io.github.kormium

import io.github.kormium.database.Database

/**
 * Mutable form of [KormiumConfig], used by the `createX { }` builders. It starts from an existing
 * config and produces a new one via `copy`, so adding a field to [KormiumConfig] only needs a
 * matching `var` here (no default is duplicated). See [KormiumBuilder.config].
 */
public class KormiumConfigBuilder internal constructor(private val base: KormiumConfig) {
    /** See [KormiumConfig.batchInsertMode]. */
    public var batchInsertMode: BatchInsertMode = base.batchInsertMode

    internal fun build(): KormiumConfig = base.copy(
        batchInsertMode = batchInsertMode,
    )
}

/**
 * Receiver of the `createX { }` builder block. Configures the database and registers a
 * [beforeStart] hook that runs once, after the connection pool is up but before the database
 * is returned — the place to run migrations (the `kormium-migrate` module, or Flyway/Liquibase).
 * Migrations are intentionally NOT a built-in concern of this builder.
 *
 * Open so a backend can add its own knobs (`SqliteBuilder` adds the `sqlite { }` block for
 * extensions and pragmas — both are per-connection concerns that [beforeStart], which runs once
 * on one already-open connection, cannot carry).
 */
public open class KormiumBuilder {
    private var config: KormiumConfig = KormiumConfig()
    private var beforeStart: (Database<Nothing>.() -> Unit)? = null

    /** Configures the [KormiumConfig] carried by the database. Calls accumulate. */
    public fun config(block: KormiumConfigBuilder.() -> Unit) {
        config = KormiumConfigBuilder(config).apply(block).build()
    }

    /**
     * Runs [block] once at startup, before the database is returned. Use it to run migrations
     * (the `kormium-migrate` module): the receiver is the database, so a migration list resolves its
     * own catalog, e.g. `beforeStart { migrate(appMigrations) }`. For Flyway/Liquibase, configure
     * them here with your connection settings (they manage their own JDBC connection). Seed data
     * belongs in a migration, not here.
     */
    public fun beforeStart(block: Database<Nothing>.() -> Unit) {
        beforeStart = block
    }

    /**
     * Builds the database with [create] (passing the configured [KormiumConfig]), then runs the
     * [beforeStart] hook on it. Called by the `createX { }` factory overloads.
     */
    public fun <D : Database<Nothing>> finish(create: (KormiumConfig) -> D): D =
        create(config).also { driver -> beforeStart?.invoke(driver) }
}
