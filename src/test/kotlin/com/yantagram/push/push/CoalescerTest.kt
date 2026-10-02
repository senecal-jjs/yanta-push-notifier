package com.yantagram.push.push

import com.yantagram.push.Platform
import com.yantagram.push.apnsToken
import com.yantagram.push.registry.Target
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class CoalescerTest {
    private var now = 0L
    private val sent = mutableListOf<Wake>()
    private val coalescer = Coalescer(30.seconds, { sent += it }, { now })
    private val target = Target(apnsToken(1), Platform.IOS)

    private fun silent(t: Target = target) = Wake(t, PushKind.SILENT)
    private fun alert(t: Target = target) = Wake(t, PushKind.ALERT)

    @Test
    fun `ten events within 30 seconds send one push now and one when the 30 seconds are up`() = runTest {
        repeat(10) {
            now = it * 2_000L
            coalescer.wake(target)
            coalescer.tick()
        }
        assertEquals(1, sent.size)

        now = 29_999
        coalescer.tick()
        assertEquals(1, sent.size)

        now = 30_000
        coalescer.tick()
        assertEquals(listOf(silent(), silent()), sent)

        // Nothing more is pending.
        now = 90_000
        coalescer.tick()
        assertEquals(2, sent.size)
        assertEquals(0, coalescer.trackedCount())
    }

    @Test
    fun `a token idle for 30 seconds is woken at once`() = runTest {
        coalescer.wake(target)
        now = 30_000
        coalescer.wake(target)
        assertEquals(2, sent.size)
    }

    @Test
    fun `tokens are coalesced independently`() = runTest {
        val other = Target(apnsToken(2), Platform.IOS)
        coalescer.wake(target)
        coalescer.wake(other)
        coalescer.wake(target)
        assertEquals(listOf(silent(), silent(other)), sent)
    }

    @Test
    fun `a forgotten token's pending wake-up is dropped`() = runTest {
        coalescer.wake(target)
        coalescer.wake(target)
        coalescer.forget(target.token)
        now = 30_000
        coalescer.tick()
        assertEquals(1, sent.size)
    }

    @Test
    fun `the first event's kind is sent at once`() = runTest {
        coalescer.wake(target, PushKind.ALERT)
        assertEquals(listOf(alert()), sent)
    }

    @Test
    fun `a silent event then a chat event inside one window produce one alert`() = runTest {
        coalescer.wake(target, PushKind.SILENT) // sent now: opens the window
        now = 2_000
        coalescer.wake(target, PushKind.SILENT)
        now = 4_000
        coalescer.wake(target, PushKind.ALERT)
        now = 6_000
        coalescer.wake(target, PushKind.SILENT)
        now = 30_000
        coalescer.tick()
        assertEquals(listOf(silent(), alert()), sent)
    }

    @Test
    fun `an alert supersedes a pending silent push, never both`() = runTest {
        coalescer.wake(target, PushKind.ALERT)
        now = 1_000
        coalescer.wake(target, PushKind.SILENT)
        coalescer.wake(target, PushKind.ALERT)
        now = 30_000
        coalescer.tick()
        now = 60_000
        coalescer.tick()
        assertEquals(listOf(alert(), alert()), sent)
    }

    @Test
    fun `a pending alert goes out after the shorter alert window`() = runTest {
        val fast = Coalescer(30.seconds, { sent += it }, { now }, alertWindow = 10.seconds)
        fast.wake(target, PushKind.SILENT)
        now = 5_000
        fast.wake(target, PushKind.SILENT)
        now = 9_999
        fast.tick()
        assertEquals(1, sent.size, "silent stays pending for the full window")

        now = 9_000
        fast.wake(target, PushKind.ALERT)
        now = 10_000
        fast.tick()
        assertEquals(listOf(silent(), alert()), sent)

        // Silent again waits the full 30 seconds from the alert.
        now = 15_000
        fast.wake(target, PushKind.SILENT)
        now = 39_999
        fast.tick()
        assertEquals(2, sent.size)
        now = 40_000
        fast.tick()
        assertEquals(listOf(silent(), alert(), silent()), sent)
    }
}
