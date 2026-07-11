package io.github.kemus.ktor

import io.github.kemus.Reply
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Render a RESP [Reply] as JSON for the generic `/command` endpoint. */
fun Reply.toJson(): JsonElement = when (this) {
    is Reply.Nil -> JsonNull
    is Reply.SimpleString -> JsonPrimitive(value)
    is Reply.BulkString -> JsonPrimitive(value)
    is Reply.Integer -> JsonPrimitive(value)
    is Reply.Double -> JsonPrimitive(value)
    is Reply.Array -> JsonArray(items.map { it.toJson() })
    is Reply.Error -> buildJsonObject { put("error", JsonPrimitive(message)) }
}
