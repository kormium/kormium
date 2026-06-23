package io.github.kormium.wasm.driver

import io.github.kormium.TypeMapper

/**
 * Normalises a JS result cell to the text form korm's text-based reads expect: `Date` → ISO-8601
 * (so Instant/LocalDateTime parse), objects (json/jsonb) → JSON, everything else via `String(...)`.
 * SQL `NULL` stays `null`. Shared by every Wasm engine.
 */
public fun cellText(value: JsAny?): String? =
    js("value == null ? null : (value instanceof Date ? value.toISOString() : (typeof value === 'object' ? JSON.stringify(value) : String(value)))")

private fun newJsArray(): JsArray<JsAny?> = js("[]")

private fun pushParam(array: JsArray<JsAny?>, value: JsString?) {
    js("array.push(value == null ? null : value)")
}

/**
 * Reduces named parameters to a positional JS array of text values (or null), in [names] order.
 * Values are bound as text (unspecified type): the server/affinity resolves the real type — the
 * libpq approach used by all the Wasm engines. A missing key is a typo, not an explicit null, so it
 * fails fast.
 */
public fun bindTextParams(
    names: List<String>,
    namedParameters: Map<String, Any?>,
    typeMapper: TypeMapper,
): JsArray<JsAny?> {
    val params = newJsArray()
    for (name in names) {
        require(namedParameters.containsKey(name)) { "No value supplied for parameter \"$name\"" }
        val mapped = typeMapper.toParameter(namedParameters[name])
        pushParam(params, mapped?.toString()?.toJsString())
    }
    return params
}
