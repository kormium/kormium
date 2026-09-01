package io.github.kormium.wasm.driver

import io.github.kormium.TypeMapper

/**
 * Normalises a JS result cell to the text form Kormium's text-based reads expect: `Date` → ISO-8601
 * (so Instant/LocalDateTime parse), objects (json/jsonb) → JSON, everything else via `String(...)`.
 * SQL `NULL` stays `null`. Shared by every Wasm engine.
 */
public fun cellText(value: JsAny?): String? =
    js("value == null ? null : (value instanceof Date ? value.toISOString() : (typeof value === 'object' ? JSON.stringify(value) : String(value)))")

private fun newJsArray(): JsArray<JsAny?> = js("[]")

private fun pushValue(array: JsArray<JsAny?>, value: JsAny?) {
    js("array.push(value == null ? null : value)")
}

// ---- binary (blob/bytea) interop ----

private fun newUint8Array(size: Int): JsAny = js("new Uint8Array(size)")
private fun setByte(array: JsAny, index: Int, value: Int) { js("array[index] = value") }
// Use a Node Buffer when available (the node-postgres/mysql2/better-sqlite3 drivers want Buffer),
// otherwise a plain Uint8Array (browser wa-sqlite). Both are accepted by their respective drivers.
private fun wrapBinary(u8: JsAny): JsAny = js("typeof Buffer !== 'undefined' ? Buffer.from(u8) : u8")
private fun binaryLength(value: JsAny): Int = js("(value && typeof value.length === 'number') ? value.length : -1")
private fun byteAt(value: JsAny, index: Int): Int = js("value[index]")

/** Converts a Kotlin [ByteArray] to a JS binary value (Buffer under Node, Uint8Array in a browser). */
public fun byteArrayToBinary(bytes: ByteArray): JsAny {
    val u8 = newUint8Array(bytes.size)
    for (i in bytes.indices) setByte(u8, i, bytes[i].toInt() and 0xFF)
    return wrapBinary(u8)
}

/** Converts a JS binary value (Buffer / Uint8Array) back to a Kotlin [ByteArray]; null for non-binary. */
public fun jsToByteArray(value: JsAny?): ByteArray? {
    if (value == null) return null
    val len = binaryLength(value)
    if (len < 0) return null
    return ByteArray(len) { byteAt(value, it).toByte() }
}

/**
 * Reduces named parameters to a positional JS array, in [names] order. Most values bind as text
 * (unspecified type — the server/affinity resolves it, the libpq approach); a [ByteArray] binds as
 * native binary (blob/bytea). A missing key is a typo, not an explicit null, so it fails fast.
 */
public fun bindTextParams(
    names: List<String>,
    namedParameters: Map<String, Any?>,
    typeMapper: TypeMapper,
): JsArray<JsAny?> {
    val params = newJsArray()
    for (name in names) {
        require(namedParameters.containsKey(name)) { "No value supplied for parameter \"$name\"" }
        when (val mapped = typeMapper.toParameter(namedParameters[name])) {
            null -> pushValue(params, null)
            is ByteArray -> pushValue(params, byteArrayToBinary(mapped))
            else -> pushValue(params, mapped.toString().toJsString())
        }
    }
    return params
}
