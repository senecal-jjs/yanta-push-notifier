package com.yantagram.push.push

import com.yantagram.push.Metrics
import com.yantagram.push.Platform
import com.yantagram.push.apnsToken
import com.yantagram.push.fcmToken
import com.yantagram.push.newRegistry
import com.yantagram.push.registry.RegistrationOptions
import com.yantagram.push.registry.Target
import com.yantagram.push.relay.RelayEvent
import com.yantagram.push.tag
import kotlinx.coroutines.test.runTest
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/** A MESSAGE packet (type 4): a chat message. */
val CHAT: String = Base64.getEncoder().encodeToString(packet(type = 4))

/** A SEALED packet (type 9, a delivery ACK): not a chat message. */
val ACK: String = Base64.getEncoder().encodeToString(packet(type = 9))

fun packet(type: Int, payload: ByteArray = ByteArray(40) { 0x5a }): ByteArray {
    val header = byteArrayOf(1, type.toByte(), 3, 0, 0, 1, -117, -49, -27, 104, 0, 0) +
        byteArrayOf(0, 0, 0, payload.size.toByte())
    return header + payload
}

class MatcherTest {
    private val now = 0L
    private val sent = mutableListOf<Wake>()
    private val metrics = Metrics()
    private val coalescer = Coalescer(kotlin.time.Duration.parse("30s"), { sent += it }, { now })
    private val keyA = ByteArray(32) { 1 }
    private val keyB = ByteArray(32) { 2 }

    private fun event(content: String, senderKey: ByteArray? = null) = RelayEvent(
        id = "e",
        createdAt = 0,
        kind = 2929,
        routingTag = tag(1),
        senderMarker = senderKey?.let { HexFormat.of().formatHex(SenderMarker.compute(it, content)) },
        content = content,
    )

    private suspend fun pushFor(platform: Platform, alerts: Boolean, content: String): PushKind {
        sent.clear()
        val registry = newRegistry()
        val token = if (platform == Platform.IOS) apnsToken(1) else fcmToken(1)
        registry.replace(token, platform, setOf(tag(1)), 0, RegistrationOptions(alerts, keyA))
        Matcher(registry, Coalescer(kotlin.time.Duration.parse("30s"), { sent += it }), metrics).onEvent(event(content))
        return sent.single().kind
    }

    @Test
    fun `iOS with alerts gets an alert for a chat message`() = runTest {
        assertEquals(PushKind.ALERT, pushFor(Platform.IOS, alerts = true, CHAT))
    }

    @Test
    fun `iOS gets a silent push for anything that isn't a chat message`() = runTest {
        assertEquals(PushKind.SILENT, pushFor(Platform.IOS, alerts = true, ACK))
        assertEquals(PushKind.SILENT, pushFor(Platform.IOS, alerts = false, ACK))
    }

    @Test
    fun `iOS without alerts gets a silent push for a chat message`() = runTest {
        assertEquals(PushKind.SILENT, pushFor(Platform.IOS, alerts = false, CHAT))
    }

    @Test
    fun `Android always gets the data message`() = runTest {
        for (alerts in listOf(true, false)) for (content in listOf(CHAT, ACK)) {
            assertEquals(PushKind.SILENT, pushFor(Platform.ANDROID, alerts, content))
        }
    }

    @Test
    fun `an unregistered tag sends nothing`() = runTest {
        val registry = newRegistry()
        registry.replace(apnsToken(1), Platform.IOS, setOf(tag(1)), 0)
        Matcher(registry, coalescer, metrics).onEvent(event(CHAT).copy(routingTag = tag(2)))
        assertEquals(emptyList(), sent)
        assertEquals(0.0, metrics.eventsMatched.count())
    }

    @Test
    fun `of two devices on a tag, only the one that didn't send is pushed`() = runTest {
        val registry = newRegistry()
        registry.replace(apnsToken(1), Platform.IOS, setOf(tag(1)), 0, RegistrationOptions(true, keyA))
        registry.replace(fcmToken(2), Platform.ANDROID, setOf(tag(1)), 0, RegistrationOptions(true, keyB))
        Matcher(registry, coalescer, metrics).onEvent(event(CHAT, senderKey = keyB))

        assertEquals(listOf(Wake(Target(apnsToken(1), Platform.IOS), PushKind.ALERT)), sent)
        assertEquals(1.0, metrics.registry.counter("push_self_skipped", "platform", "android").count())
    }

    @Test
    fun `an event without a sender marker skips no device`() = runTest {
        val registry = newRegistry()
        registry.replace(apnsToken(1), Platform.IOS, setOf(tag(1)), 0, RegistrationOptions(true, keyA))
        Matcher(registry, coalescer, metrics).onEvent(event(CHAT, senderKey = null))
        assertEquals(1, sent.size)
    }

    @Test
    fun `a device without a key is pushed for its own events`() = runTest {
        val registry = newRegistry()
        registry.replace(apnsToken(1), Platform.IOS, setOf(tag(1)), 0, RegistrationOptions(true, null))
        Matcher(registry, coalescer, metrics).onEvent(event(CHAT, senderKey = keyA))
        assertEquals(1, sent.size)
    }

    @Test
    fun `a chat event from the token's own device doesn't turn a pending push into an alert`() = runTest {
        val registry = newRegistry()
        registry.replace(apnsToken(1), Platform.IOS, setOf(tag(1)), 0, RegistrationOptions(true, keyA))
        var clock = 0L
        val windowed = Coalescer(kotlin.time.Duration.parse("30s"), { sent += it }, { clock })
        val matcher = Matcher(registry, windowed, metrics)

        matcher.onEvent(event(ACK, senderKey = keyB)) // silent, sent now
        clock = 1_000
        matcher.onEvent(event(ACK, senderKey = keyB)) // pending silent
        matcher.onEvent(event(CHAT, senderKey = keyA)) // our own message: skipped
        clock = 30_000
        windowed.tick()

        val target = Target(apnsToken(1), Platform.IOS)
        assertEquals(listOf(Wake(target, PushKind.SILENT), Wake(target, PushKind.SILENT)), sent)
    }
}
