package io.github.kormium

// kotlin-logging has no wasmWasi artifact (7.0.3), and Kotlin/Wasm WASI has no JS console
// to log to. Core's trace diagnostics are non-essential, so the facade is a no-op here.
// Swap to a real delegate once kotlin-logging ships wasmWasi (or wire stdout if needed).
internal actual fun kormiumLogger(): KormiumLogger = object : KormiumLogger {
    override val isTraceEnabled: Boolean get() = false
    override fun traceMessage(msg: String) {}
}
