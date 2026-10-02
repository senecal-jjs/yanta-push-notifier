package com.yantagram.push.push

import com.eatthepath.pushy.apns.DeliveryPriority
import com.eatthepath.pushy.apns.PushType
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification
import com.yantagram.push.apnsToken
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ApnsSenderTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")

    private class FakeGateway(vararg responses: ApnsResponse) : ApnsGateway {
        val queue = ArrayDeque(responses.toList())
        val sent = mutableListOf<SimpleApnsPushNotification>()
        override suspend fun send(notification: SimpleApnsPushNotification): ApnsResponse {
            sent += notification
            return queue.removeFirst()
        }
    }

    private fun sender(gateway: ApnsGateway) = ApnsSender(gateway, "com.yantagram.yantagram", { now })

    @Test
    fun `the notification matches the spec exactly`() {
        val n = sender(FakeGateway()).notification(apnsToken(1))
        assertEquals(apnsToken(1), n.token)
        assertEquals("com.yantagram.yantagram", n.topic)
        assertEquals("""{"aps":{"content-available":1}}""", n.payload)
        assertEquals(PushType.BACKGROUND, n.pushType)
        assertEquals(DeliveryPriority.CONSERVE_POWER, n.priority)
        assertEquals(5, n.priority.code)
        assertEquals(now.plusSeconds(3600), n.expiration)
        assertEquals("wake", n.collapseId)
    }

    @Test
    fun `the alert matches the spec exactly`() {
        val n = sender(FakeGateway()).notification(apnsToken(1), PushKind.ALERT)
        assertEquals("com.yantagram.yantagram", n.topic)
        assertEquals(
            Json.parseToJsonElement(
                """{"aps":{"alert":{"title":"Yantagram","body":"New message"},
                   "sound":"default","mutable-content":1,"content-available":1}}""",
            ),
            Json.parseToJsonElement(n.payload),
        )
        assertEquals(PushType.ALERT, n.pushType)
        assertEquals(10, n.priority.code)
        assertEquals(now.plusSeconds(24 * 3600), n.expiration)
        assertEquals(null, n.collapseId)
    }

    @Test
    fun `sends the kind it is asked for`() = runTest {
        val gateway = FakeGateway(ApnsResponse(200, null), ApnsResponse(200, null))
        sender(gateway).send(apnsToken(1), PushKind.ALERT)
        sender(gateway).send(apnsToken(1), PushKind.SILENT)
        assertEquals(listOf(PushType.ALERT, PushType.BACKGROUND), gateway.sent.map { it.pushType })
    }

    @Test
    fun `200 is sent`() = runTest {
        assertEquals(PushResult.Sent, sender(FakeGateway(ApnsResponse(200, null))).send(apnsToken(1), PushKind.SILENT))
    }

    @Test
    fun `410 and BadDeviceToken invalidate the token`() = runTest {
        assertEquals(
            PushResult.InvalidToken("apns_410"),
            sender(FakeGateway(ApnsResponse(410, "Unregistered"))).send(apnsToken(1), PushKind.SILENT),
        )
        assertEquals(
            PushResult.InvalidToken("apns_bad_device_token"),
            sender(FakeGateway(ApnsResponse(400, "BadDeviceToken"))).send(apnsToken(1), PushKind.SILENT),
        )
    }

    @Test
    fun `other 400s fail without removing the token`() = runTest {
        assertEquals(
            PushResult.Failed("apns_400_DeviceTokenNotForTopic"),
            sender(FakeGateway(ApnsResponse(400, "DeviceTokenNotForTopic"))).send(apnsToken(1), PushKind.SILENT),
        )
    }

    @Test
    fun `429 and 5xx are retried`() = runTest {
        val gateway = FakeGateway(ApnsResponse(429, "TooManyRequests"), ApnsResponse(503, null), ApnsResponse(200, null))
        assertEquals(PushResult.Sent, sender(gateway).send(apnsToken(1), PushKind.SILENT))
        assertEquals(3, gateway.sent.size)
    }

    @Test
    fun `gives up after 3 attempts`() = runTest {
        val gateway = FakeGateway(*Array(5) { ApnsResponse(500, "InternalServerError") })
        assertEquals(PushResult.Failed("apns_500"), sender(gateway).send(apnsToken(1), PushKind.SILENT))
        assertEquals(3, gateway.sent.size)
    }
}
