package com.yantagram.push.push

import com.yantagram.push.Metrics
import com.yantagram.push.Platform
import com.yantagram.push.apnsToken
import com.yantagram.push.fcmToken
import com.yantagram.push.newRegistry
import com.yantagram.push.registry.Target
import com.yantagram.push.tag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeSender(override val platform: Platform, var result: PushResult = PushResult.Sent) : PushSender {
    val sent = mutableListOf<String>()
    val kinds = mutableListOf<PushKind>()
    override suspend fun send(token: String, kind: PushKind): PushResult {
        sent += token
        kinds += kind
        return result
    }
}

class PushDispatcherTest {
    private val ios = FakeSender(Platform.IOS)
    private val android = FakeSender(Platform.ANDROID)
    private val metrics = Metrics()

    @Test
    fun `routes each wake-up to its platform's sender`() = runTest {
        val registry = newRegistry()
        registry.replace(apnsToken(1), Platform.IOS, setOf(tag(1)), 0)
        registry.replace(fcmToken(1), Platform.ANDROID, setOf(tag(1)), 0)
        val dispatcher = PushDispatcher(mapOf(Platform.IOS to ios, Platform.ANDROID to android), registry, metrics)

        dispatcher.deliver(Wake(Target(apnsToken(1), Platform.IOS), PushKind.ALERT))
        dispatcher.deliver(Wake(Target(fcmToken(1), Platform.ANDROID), PushKind.SILENT))

        assertEquals(listOf(apnsToken(1)), ios.sent)
        assertEquals(listOf(fcmToken(1)), android.sent)
        assertEquals(1.0, metrics.registry.counter("push_sent", "platform", "ios", "type", "alert").count())
    }

    @Test
    fun `an invalid token is removed from the registry`() = runTest {
        val registry = newRegistry()
        registry.replace(apnsToken(1), Platform.IOS, setOf(tag(1)), 0)
        ios.result = PushResult.InvalidToken("apns_410")
        val forgotten = mutableListOf<String>()
        val dispatcher = PushDispatcher(mapOf(Platform.IOS to ios), registry, metrics, onTokenRemoved = { forgotten += it })

        dispatcher.deliver(Wake(Target(apnsToken(1), Platform.IOS), PushKind.SILENT))

        assertFalse(registry.contains(apnsToken(1)))
        assertEquals(listOf(apnsToken(1)), forgotten)
        assertEquals(1.0, metrics.registry.counter("push_failed", "platform", "ios", "reason", "apns_410").count())
    }

    @Test
    fun `a token unregistered while queued is not pushed`() = runTest {
        val registry = newRegistry()
        val dispatcher = PushDispatcher(mapOf(Platform.IOS to ios), registry, metrics)
        dispatcher.deliver(Wake(Target(apnsToken(1), Platform.IOS), PushKind.SILENT))
        assertTrue(ios.sent.isEmpty())
    }

    @Test
    fun `a full queue drops the oldest wake-ups and counts them`() = runTest {
        val dispatcher = PushDispatcher(emptyMap(), newRegistry(), metrics, capacity = 2)
        repeat(5) { dispatcher.enqueue(Wake(Target(apnsToken(it), Platform.IOS), PushKind.SILENT)) }
        assertEquals(3.0, metrics.registry.counter("push_dropped", "reason", "queue_full").count())
    }
}
