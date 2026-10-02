package com.yantagram.push.push

import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SenderMarkerTest {
    private val hex = HexFormat.of()
    private val key = hex.parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")

    @Test
    fun `matches the app's test vector`() {
        assertEquals("26a5095bd9ea64767b9207dfaa053ada", hex.formatHex(SenderMarker.compute(key, "AQID+vv8")))
        assertTrue(SenderMarker.isFrom(key, "AQID+vv8", hex.parseHex("26a5095bd9ea64767b9207dfaa053ada")))
    }

    @Test
    fun `a wrong key or other content doesn't match`() {
        val marker = hex.parseHex("26a5095bd9ea64767b9207dfaa053ada")
        assertFalse(SenderMarker.isFrom(ByteArray(32), "AQID+vv8", marker))
        assertFalse(SenderMarker.isFrom(key, "AQID+vv9", marker))
    }
}
