package io.github.kormium

import io.github.oshai.kotlinlogging.KotlinLogging

// Shared by jvm, android, native, js and wasmJs (everything but wasmWasi): delegate the
// facade to kotlin-logging. One named logger is enough — core only emits trace.
private val delegate = KotlinLogging.logger("io.github.kormium")

internal actual fun kormiumLogger(): KormiumLogger = object : KormiumLogger {
    override fun trace(msg: () -> String) = delegate.trace(msg)
}
