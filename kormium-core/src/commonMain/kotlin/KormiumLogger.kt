package io.github.kormium

/**
 * Minimal internal logging facade. Core only ever emits lazy `trace` diagnostics, so the
 * surface is intentionally tiny. It exists to keep `commonMain` free of a hard dependency
 * on kotlin-logging, which has no `wasmWasi` artifact (as of 7.0.3): every other target
 * delegates to kotlin-logging via `loggingMain`, while `wasmWasi` gets a no-op [actual].
 *
 * When kotlin-logging ships a wasmWasi target, the wasmWasi actual can switch from no-op
 * to a real delegate with no change to call sites.
 */
internal interface KormiumLogger {
    fun trace(msg: () -> String)
}

/** Returns the logger backing core diagnostics on the current target. */
internal expect fun kormiumLogger(): KormiumLogger
