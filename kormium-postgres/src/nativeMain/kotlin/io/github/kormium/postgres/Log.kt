package io.github.kormium.postgres

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Marker
import io.github.oshai.kotlinlogging.KLogger as ActualKLogger

internal object PgknMarker : Marker {
    override fun getName() = "PGKN"
}

internal class KLogger(private val actualKLogger: ActualKLogger) : ActualKLogger by actualKLogger {
    constructor(name: String) : this(KotlinLogging.logger(name))

    override fun trace(message: () -> Any?) = trace(PgknMarker, message)

// Might want to override this to give the ability to turn on/off logs for PGKN
// override fun isLoggingEnabledFor(level: Level, marker: Marker?) = level.isLoggingEnabled() && ?
}
