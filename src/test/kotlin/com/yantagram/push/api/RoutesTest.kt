package com.yantagram.push.api

import com.yantagram.push.Metrics
import com.yantagram.push.Platform
import com.yantagram.push.apnsToken
import com.yantagram.push.fcmToken
import com.yantagram.push.newRegistry
import com.yantagram.push.pushModule
import com.yantagram.push.registry.Registry
import com.yantagram.push.registry.RegistrationOptions
import com.yantagram.push.registry.Target
import com.yantagram.push.tag
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutesTest {
    private fun body(token: String, platform: String, tags: List<String>, alerts: Boolean = true, self: String? = null) =
        buildJsonObject {
            put("token", token)
            put("platform", platform)
            put("tags", JsonArray(tags.map(::JsonPrimitive)))
            put("alerts", alerts)
            if (self != null) put("self", self)
        }.toString()

    private fun routesTest(
        registry: Registry = newRegistry(),
        perMinute: Int = 1_000,
        block: suspend ApplicationTestBuilder.(Registry) -> Unit,
    ) = testApplication {
        application { pushModule(registry, { true }, Metrics(), registrationsPerMinute = perMinute) }
        block(registry)
    }

    private suspend fun ApplicationTestBuilder.register(raw: String, ip: String = "1.1.1.1"): HttpResponse =
        client.put("/push/register") {
            contentType(ContentType.Application.Json)
            header("Fly-Client-IP", ip)
            setBody(raw)
        }

    private suspend fun ApplicationTestBuilder.unregister(raw: String): HttpResponse =
        client.delete("/push/register") {
            contentType(ContentType.Application.Json)
            setBody(raw)
        }

    @Test
    fun `registers an iOS and an Android token`() = routesTest { registry ->
        assertEquals(HttpStatusCode.NoContent, register(body(apnsToken(1), "ios", listOf(tag(1)))).status)
        assertEquals(HttpStatusCode.NoContent, register(body(fcmToken(1), "android", listOf(tag(1)))).status)
        assertEquals(
            setOf(Target(apnsToken(1), Platform.IOS), Target(fcmToken(1), Platform.ANDROID)),
            registry.match(tag(1)).map { it.target }.toSet(),
        )
    }

    @Test
    fun `upper-case APNs tokens are stored lower-case`() = routesTest { registry ->
        val upper = apnsToken(1).uppercase()
        assertEquals(HttpStatusCode.NoContent, register(body(upper, "ios", listOf(tag(1)))).status)
        assertTrue(registry.contains(apnsToken(1)))
        assertEquals(HttpStatusCode.NoContent, unregister("""{"token":"$upper"}""").status)
        assertFalse(registry.contains(apnsToken(1)))
    }

    @Test
    fun `stores alerts and the sender key, replaces them, and deletes them`() = routesTest { registry ->
        val key = "0f".repeat(32)
        register(body(apnsToken(1), "ios", listOf(tag(1)), alerts = true, self = key))
        assertEquals(RegistrationOptions(true, ByteArray(32) { 0x0f }), registry.optionsOf(apnsToken(1)))

        register(body(apnsToken(1), "ios", listOf(tag(1)), alerts = false))
        assertEquals(RegistrationOptions(false, null), registry.optionsOf(apnsToken(1)))

        register(body(apnsToken(1), "ios", listOf(tag(1)), self = key))
        unregister("""{"token":"${apnsToken(1)}"}""")
        assertEquals(null, registry.optionsOf(apnsToken(1)))
    }

    @Test
    fun `an empty tag list unregisters`() = routesTest { registry ->
        register(body(apnsToken(1), "ios", listOf(tag(1))))
        assertEquals(HttpStatusCode.NoContent, register(body(apnsToken(1), "ios", emptyList())).status)
        assertFalse(registry.contains(apnsToken(1)))
    }

    @Test
    fun `rejects malformed requests with 400`() = routesTest { registry ->
        val cases = mapOf(
            "not json" to "{nope",
            "missing tags" to """{"token":"${apnsToken(1)}","platform":"ios","alerts":true}""",
            "missing alerts" to """{"token":"${apnsToken(1)}","platform":"ios","tags":["${tag(1)}"]}""",
            "alerts not a boolean" to """{"token":"${apnsToken(1)}","platform":"ios","tags":["${tag(1)}"],"alerts":"yes"}""",
            "alerts as a quoted boolean" to """{"token":"${apnsToken(1)}","platform":"ios","tags":["${tag(1)}"],"alerts":"true"}""",
            "short self" to body(apnsToken(1), "ios", listOf(tag(1)), self = "ab".repeat(31)),
            "upper-case self" to body(apnsToken(1), "ios", listOf(tag(1)), self = "AB".repeat(32)),
            "non-hex self" to body(apnsToken(1), "ios", listOf(tag(1)), self = "zz".repeat(32)),
            "self not a string" to """{"token":"${apnsToken(1)}","platform":"ios","tags":["${tag(1)}"],"alerts":true,"self":5}""",
            "tags not a list" to """{"token":"${apnsToken(1)}","platform":"ios","tags":"x","alerts":true}""",
            "unknown platform" to body(apnsToken(1), "web", listOf(tag(1))),
            "short APNs token" to body("abcd", "ios", listOf(tag(1))),
            "non-hex APNs token" to body("z".repeat(64), "ios", listOf(tag(1))),
            "short FCM token" to body("a".repeat(99), "android", listOf(tag(1))),
            "long FCM token" to body("a".repeat(301), "android", listOf(tag(1))),
            "FCM token with bad characters" to body("a".repeat(120) + "/=", "android", listOf(tag(1))),
            "short tag" to body(apnsToken(1), "ios", listOf("abc")),
            "upper-case tag" to body(apnsToken(1), "ios", listOf(tag(10).uppercase().replace('0', 'A'))),
            "non-hex tag" to body(apnsToken(1), "ios", listOf("g".repeat(32))),
        )
        for ((name, raw) in cases) {
            val response = register(raw)
            assertEquals(HttpStatusCode.BadRequest, response.status, name)
            assertFalse(response.bodyAsText().contains(apnsToken(1)), "$name echoes the token")
        }
        assertEquals(0, registry.size)
    }

    @Test
    fun `rejects more than 512 tags and bodies over 64 KB with 413`() = routesTest { registry ->
        val tags = (0..MAX_TAGS).map(::tag)
        assertEquals(HttpStatusCode.PayloadTooLarge, register(body(apnsToken(1), "ios", tags)).status)
        assertEquals(HttpStatusCode.NoContent, register(body(apnsToken(1), "ios", tags.take(MAX_TAGS))).status)

        val padded = """{"token":"${apnsToken(2)}","platform":"ios","tags":["${tag(1)}"],"alerts":true,"pad":"${"x".repeat(70_000)}"}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, register(padded).status)
        assertEquals(1, registry.size)
    }

    @Test
    fun `DELETE always returns 204`() = routesTest { registry ->
        assertEquals(HttpStatusCode.NoContent, unregister("""{"token":"${apnsToken(9)}"}""").status)
        register(body(apnsToken(1), "ios", listOf(tag(1))))
        assertEquals(HttpStatusCode.NoContent, unregister("""{"token":"${apnsToken(1)}"}""").status)
        assertEquals(0, registry.size)
    }

    @Test
    fun `rate limits registrations per client IP`() = routesTest(perMinute = 3) {
        repeat(3) {
            assertEquals(HttpStatusCode.NoContent, register(body(apnsToken(it), "ios", listOf(tag(1))), ip = "2.2.2.2").status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, register(body(apnsToken(5), "ios", listOf(tag(1))), ip = "2.2.2.2").status)
        assertEquals(HttpStatusCode.NoContent, register(body(apnsToken(5), "ios", listOf(tag(1))), ip = "3.3.3.3").status)
    }

    @Test
    fun `answers 503 when registrations are full`() = routesTest(registry = newRegistry(max = 1)) {
        assertEquals(HttpStatusCode.NoContent, register(body(apnsToken(1), "ios", listOf(tag(1)))).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, register(body(apnsToken(2), "ios", listOf(tag(1)))).status)
    }

    @Test
    fun `health reports the relay and the registration count`() = routesTest {
        register(body(apnsToken(1), "ios", listOf(tag(1))))
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(Json.parseToJsonElement("""{"relay":"connected","registrations":1}"""),
            Json.parseToJsonElement(response.bodyAsText()))
    }
}
