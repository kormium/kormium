package io.github.kormium.ktor

import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log

/** Configuration for the [Kormium] plugin. */
public class KormiumConfig {
    internal val managed = mutableListOf<AutoCloseable>()

    /**
     * Registers [db] to be `close`d when the application stops. Accepts any [AutoCloseable] —
     * both `Database` and `SuspendDatabase` (incl. the r2dbc driver) qualify. Use this only if
     * you are NOT managing the database's lifecycle elsewhere — Ktor's built-in DI already closes
     * `AutoCloseable` dependencies on shutdown, so registering it there makes this unnecessary.
     */
    public fun manage(db: AutoCloseable) {
        managed += db
    }
}

/**
 * A minimal lifecycle plugin: closes every database registered via [KormiumConfig.manage] when the
 * application stops. It does not store or expose the databases — access stays through your own
 * reference, a DI container, or the `kormium-ktor-di` / `kormium-ktor-koin` helpers.
 *
 * ```
 * install(Kormium) { manage(database) }
 * ```
 */
public val Kormium: io.ktor.server.application.ApplicationPlugin<KormiumConfig> = createApplicationPlugin(name = "Kormium", createConfiguration = ::KormiumConfig) {
    val managed = pluginConfig.managed.toList()
    on(MonitoringEvent(ApplicationStopped)) { application ->
        managed.forEach { db ->
            runCatching { db.close() }.onFailure { application.log.warn("Failed to close database", it) }
        }
    }
}
