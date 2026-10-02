package com.yantagram.push.push

import com.yantagram.push.fcmToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class FcmSenderTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun sender(vararg responses: MockRequestHandleScope.() -> HttpResponseData): FcmSender {
        val queue = ArrayDeque(responses.toList())
        val engine = MockEngine { request ->
            requests += request
            queue.removeFirst()(this)
        }
        return FcmSender(HttpClient(engine), "yantagram", { "access-token" })
    }

    private fun error(status: HttpStatusCode, code: String, message: String, errorCode: String? = null):
        MockRequestHandleScope.() -> HttpResponseData = {
        val details = errorCode?.let {
            ""","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$it"}]"""
        }.orEmpty()
        respond(
            """{"error":{"code":${status.value},"message":"$message","status":"$code"$details}}""",
            status,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private val ok: MockRequestHandleScope.() -> HttpResponseData =
        { respond("""{"name":"projects/yantagram/messages/1"}""", HttpStatusCode.OK) }

    @Test
    fun `the request matches the spec exactly`() = runTest {
        assertEquals(PushResult.Sent, sender(ok).send(fcmToken(1), PushKind.SILENT))

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://fcm.googleapis.com/v1/projects/yantagram/messages:send", request.url.toString())
        assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
        val body = (request.body as TextContent)
        assertEquals("application/json", body.contentType.withoutParameters().toString())
        assertEquals(
            Json.parseToJsonElement(
                """{"message":{"token":"${fcmToken(1)}","data":{"w":"1"},
                   "android":{"priority":"HIGH","ttl":"3600s","collapse_key":"wake"}}}""",
            ),
            Json.parseToJsonElement(body.text),
        )
    }

    @Test
    fun `UNREGISTERED invalidates the token`() = runTest {
        val result = sender(error(HttpStatusCode.NotFound, "NOT_FOUND", "Requested entity was not found.", "UNREGISTERED"))
            .send(fcmToken(1), PushKind.SILENT)
        assertEquals(PushResult.InvalidToken("fcm_unregistered"), result)
    }

    @Test
    fun `INVALID_ARGUMENT about the token invalidates it`() = runTest {
        val result = sender(
            error(HttpStatusCode.BadRequest, "INVALID_ARGUMENT", "The registration token is not a valid FCM registration token", "INVALID_ARGUMENT"),
        ).send(fcmToken(1), PushKind.SILENT)
        assertEquals(PushResult.InvalidToken("fcm_invalid_token"), result)
    }

    @Test
    fun `other INVALID_ARGUMENTs fail without removing the token`() = runTest {
        val result = sender(error(HttpStatusCode.BadRequest, "INVALID_ARGUMENT", "Invalid value at 'message.android.ttl'"))
            .send(fcmToken(1), PushKind.SILENT)
        assertEquals(PushResult.Failed("fcm_400"), result)
    }

    @Test
    fun `429 and 5xx are retried`() = runTest {
        val tooMany: MockRequestHandleScope.() -> HttpResponseData =
            { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "2")) }
        val unavailable = error(HttpStatusCode.ServiceUnavailable, "UNAVAILABLE", "try later")
        assertEquals(PushResult.Sent, sender(tooMany, unavailable, ok).send(fcmToken(1), PushKind.SILENT))
        assertEquals(3, requests.size)
    }

    @Test
    fun `gives up after 3 attempts`() = runTest {
        val internal = error(HttpStatusCode.InternalServerError, "INTERNAL", "oops")
        assertEquals(PushResult.Failed("fcm_500"), sender(internal, internal, internal, ok).send(fcmToken(1), PushKind.SILENT))
        assertEquals(3, requests.size)
    }
}
