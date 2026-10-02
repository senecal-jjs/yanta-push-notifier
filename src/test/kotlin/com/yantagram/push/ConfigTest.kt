package com.yantagram.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigTest {
    private val body = "MIGTAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBHkwdwIBAQQg" + "A".repeat(40) + "Ab+/"
    private val pem = "-----BEGIN PRIVATE KEY-----\n" + body.chunked(64).joinToString("\n") + "\n-----END PRIVATE KEY-----\n"

    private fun parse(raw: String) = mutableListOf<String>().let { errors -> Config.pemKey("KEY", raw, errors) to errors }

    @Test
    fun `keeps a proper PEM key and repairs mangled ones`() {
        val mangled = listOf(
            pem,
            pem.replace("\n", "\\n"),                       // literal \n
            pem.replace("\n", " "),                          // newlines became spaces
            pem.replace("\n", "\r\n"),                       // Windows line endings
            body,                                            // just the base64 body
            "  " + pem.trim().replace("\n", " ") + "  ",
        )
        for (raw in mangled) assertEquals(pem, parse(raw).first, raw)
    }

    @Test
    fun `rejects values that aren't a key without echoing them`() {
        for (raw in listOf("op://vault/item/key", "/secrets/AuthKey.p8", "-----BEGIN PRIVATE KEY----- abc")) {
            val (_, errors) = parse(raw)
            assertEquals(1, errors.size, raw)
            assertTrue(raw !in errors.single())
        }
    }
}
