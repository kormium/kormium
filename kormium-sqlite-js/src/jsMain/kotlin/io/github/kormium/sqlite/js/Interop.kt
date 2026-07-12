package io.github.kormium.sqlite.js

/**
 * Normalises a JS result cell to the text form korm's text-based reads expect: `Date` → ISO-8601
 * (so Instant/LocalDateTime parse), objects (json/jsonb) → JSON, everything else via `String(...)`.
 * SQL `NULL` stays `null`. Kotlin/JS counterpart of `kormium-wasm-driver`'s `cellText`.
 */
internal fun cellText(value: Any?): String? =
    js("value == null ? null : (value instanceof Date ? value.toISOString() : (typeof value === 'object' ? JSON.stringify(value) : String(value)))")

// Each js(...) below references only its own function parameters, so the inlined JS names line up
// regardless of how the compiler mangles surrounding scopes.
private fun uint8ArrayFrom(bytes: ByteArray): Any = js("Uint8Array.from(bytes)")
private fun binaryLength(value: Any): Int = js("(value && typeof value.length === 'number') ? value.length : -1")
private fun byteAt(value: Any, index: Int): Int = js("value[index]")

/** Converts a Kotlin [ByteArray] to a JS binary value (Uint8Array — what browser wa-sqlite binds). */
internal fun byteArrayToBinary(bytes: ByteArray): Any = uint8ArrayFrom(bytes)

/** Converts a JS binary value (Uint8Array / Buffer) back to a Kotlin [ByteArray]; null for non-binary. */
internal fun jsToByteArray(value: Any?): ByteArray? {
    if (value == null) return null
    val len = binaryLength(value)
    if (len < 0) return null
    return ByteArray(len) { byteAt(value, it).toByte() }
}
