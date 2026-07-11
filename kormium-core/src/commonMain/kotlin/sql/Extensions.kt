package io.github.kormium.sql

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid
import io.github.kormium.resultset.ResultSet

public fun ResultSet.getUUID(columnIndex: Int): Uuid? {
    return getString(columnIndex)?.let { Uuid.parse(it) }
}

// Note: ResultSet already provides getInstant() as a member (with the required
// Postgres "space -> T" ISO-8601 fix), so no extension is defined here.

public fun ResultSet.getJson(columnIndex: Int): JsonElement? {
    return getString(columnIndex)?.let { Json.parseToJsonElement(it) }
}
