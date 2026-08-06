package io.github.kormium

/**
 * Minimal internal logging facade. Core only ever emits lazy `trace` diagnostics, so the
 * surface is intentionally tiny. It exists to keep `commonMain` free of a hard dependency
 * on kotlin-logging, which has no `wasmWasi` artifact (as of 7.0.3): every other target
 * delegates to kotlin-logging via `loggingMain`, while `wasmWasi` gets a no-op [actual].
 *
 * When kotlin-logging ships a wasmWasi target, the wasmWasi actual can switch from no-op
 * to a real delegate with no change to call sites.
 *
 * The message is taken already-formatted ([traceMessage]) rather than as a lambda, because
 * an interface method cannot be inlined: a `trace { "... $x" }` member would allocate its
 * capturing closure on every call even when tracing is off. Call sites instead use the
 * inline [trace] extension below, which only builds the message inside the enabled branch.
 */
internal interface KormiumLogger {
    /** Whether trace output is actually consumed; guards message construction at call sites. */
    val isTraceEnabled: Boolean

    /** Emits an already-formatted trace message. Call [trace] instead. */
    fun traceMessage(msg: String)
}

/**
 * Lazily emits a trace message. Inline, so [msg] is only constructed — and only allocated —
 * when tracing is enabled. Kormium calls this on per-row and per-field paths, where an
 * unconditional closure allocation is measurable on Kotlin/Native (which, unlike the JVM's
 * JIT, does not eliminate it).
 */
internal inline fun KormiumLogger.trace(msg: () -> String) {
    if (isTraceEnabled) traceMessage(msg())
}

/** Returns the logger backing core diagnostics on the current target. */
internal expect fun kormiumLogger(): KormiumLogger
