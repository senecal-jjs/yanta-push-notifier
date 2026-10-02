package com.yantagram.push.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The parts of a Nostr event the service uses. [content] is the base64 packet: only its
 * plaintext header is read (PacketKind) and it is MACed for the sender marker. Never logged.
 */
data class RelayEvent(
    val id: String,
    val createdAt: Long,
    val kind: Int,
    val routingTag: String?,
    /** The `s` tag: a sender marker, or null when absent or malformed. */
    val senderMarker: String? = null,
    val content: String = "",
)

/** Messages from the relay (NIP-01). */
sealed interface RelayMessage {
    data class Event(val subscriptionId: String, val event: RelayEvent) : RelayMessage
    data class Eose(val subscriptionId: String) : RelayMessage
    data class Closed(val subscriptionId: String, val message: String) : RelayMessage
    data class Notice(val message: String) : RelayMessage
    data object Other : RelayMessage
}

object NostrMessages {
    private val json = Json { ignoreUnknownKeys = true }
    private val HEX32 = Regex("^[0-9a-f]{32}$")

    fun req(subscriptionId: String, kind: Int, since: Long): String = buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subscriptionId))
        add(buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(kind)) }
            put("since", since)
        })
    }.toString()

    fun close(subscriptionId: String): String =
        buildJsonArray { add(JsonPrimitive("CLOSE")); add(JsonPrimitive(subscriptionId)) }.toString()

    /** Parses one relay message; anything unexpected becomes [RelayMessage.Other]. */
    fun parse(text: String): RelayMessage = try {
        val array = json.parseToJsonElement(text).jsonArray
        when (array.getOrNull(0)?.jsonPrimitive?.contentOrNull) {
            "EVENT" -> parseEvent(array)
            "EOSE" -> RelayMessage.Eose(array[1].jsonPrimitive.content)
            "CLOSED" -> RelayMessage.Closed(
                array[1].jsonPrimitive.content,
                array.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            "NOTICE" -> RelayMessage.Notice(array.getOrNull(1)?.jsonPrimitive?.contentOrNull.orEmpty())
            else -> RelayMessage.Other
        }
    } catch (_: Exception) {
        RelayMessage.Other
    }

    private fun parseEvent(array: JsonArray): RelayMessage {
        val subscriptionId = array[1].jsonPrimitive.content
        val event = array[2] as? JsonObject ?: return RelayMessage.Other
        val id = event["id"]?.jsonPrimitive?.contentOrNull ?: return RelayMessage.Other
        val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: return RelayMessage.Other
        val kind = event["kind"]?.jsonPrimitive?.intOrNull ?: return RelayMessage.Other
        val tags = (event["tags"] as? JsonArray).orEmpty().mapNotNull { it as? JsonArray }
        fun single(name: String) = tags
            .filter { it.getOrNull(0)?.jsonPrimitive?.contentOrNull == name }
            .mapNotNull { it.getOrNull(1)?.jsonPrimitive?.contentOrNull }
            .singleOrNull()
            ?.takeIf { HEX32.matches(it) }
        val content = event["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // The relay's write policy allows exactly one well-formed routing tag and at most
        // one sender marker; be strict anyway.
        return RelayMessage.Event(
            subscriptionId,
            RelayEvent(id, createdAt, kind, routingTag = single("y"), senderMarker = single("s"), content = content),
        )
    }
}
