package io.github.kormium

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level

// Shared by jvm, android, native, js and wasmJs (everything but wasmWasi): delegate the
// facade to kotlin-logging. One named logger is enough — core only emits trace.
private val delegate = KotlinLogging.logger("io.github.kormium")

internal actual fun kormiumLogger(): KormiumLogger = object : KormiumLogger {
    override val isTraceEnabled: Boolean get() = delegate.isLoggingEnabledFor(Level.TRACE, null)
    override fun traceMessage(msg: String) = delegate.trace { msg }
}
