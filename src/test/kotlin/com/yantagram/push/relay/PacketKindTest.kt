package com.yantagram.push.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketKindTest {
    /** The app's shared vectors (services/__tests__/fixtures/packet-kind-vectors.json). */
    @Test
    fun `classifies every shared vector`() {
        val text = checkNotNull(javaClass.getResource("/packet-kind-vectors.json")).readText()
        val vectors = Json.parseToJsonElement(text).jsonObject["vectors"]!!.jsonArray
        assertTrue(vectors.size >= 7)
        for (vector in vectors.map { it.jsonObject }) {
            val name = vector["name"]!!.jsonPrimitive.content
            val expected = vector["chatMessage"]!!.jsonPrimitive.boolean
            assertEquals(expected, PacketKind.isChatMessage(vector["base64"]!!.jsonPrimitive.content), name)
        }
    }

    @Test
    fun `malformed packets are not chat messages`() {
        assertFalse(PacketKind.isChatMessage("not base64!"))
        assertFalse(PacketKind.isChatMessage(""))
        assertFalse(PacketKind.isChatMessage(ByteArray(15)))
        // Version 2.
        assertFalse(PacketKind.isChatMessage(byteArrayOf(2, 4) + ByteArray(14)))
        // A length beyond the packet.
        assertFalse(PacketKind.isChatMessage(byteArrayOf(1, 4) + ByteArray(10) + byteArrayOf(0, 0, 0, 1)))
        // A compressed fragment whose zlib stream is garbage.
        val garbage = byteArrayOf(1, 8, 0) + ByteArray(8) + byteArrayOf(1, 0, 0, 0, 12) +
            byteArrayOf(0, 0, 1, 0) + ByteArray(8) { 0x7f }
        assertFalse(PacketKind.isChatMessage(garbage))
    }
}
