package com.yantagram.push.push

import com.yantagram.push.registry.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Coalesces wake-ups per token. A message arrives as many events (fragments, ACKs) and
 * one push catches them all, because the app then catches up from the relay itself.
 *
 * - At most one push per token per [window].
 * - If any event coalesced into a pending push was a chat message, the pending push is
 *   sent as an alert; an alert supersedes a pending silent push, never both.
 * - A pending alert goes out once [alertWindow] (shorter) has passed since the last push,
 *   so a reply right after a previous push doesn't wait the whole [window].
 */
class Coalescer(
    private val window: Duration,
    private val send: (Wake) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val alertWindow: Duration = window,
) {
    private class Entry(var target: Target, var lastSentAt: Long) {
        var pending: PushKind? = null
    }

    private val mutex = Mutex()
    private val entries = HashMap<String, Entry>()
    private val windowMs = window.inWholeMilliseconds
    private val alertWindowMs = minOf(alertWindow, window).inWholeMilliseconds

    /** Wakes [target] now, or once its window is up. */
    suspend fun wake(target: Target, kind: PushKind = PushKind.SILENT) = mutex.withLock {
        val now = nowMillis()
        val entry = entries[target.token]
        if (entry == null) {
            entries[target.token] = Entry(target, now)
            send(Wake(target, kind))
            return@withLock
        }
        entry.target = target
        val pending = if (entry.pending == PushKind.ALERT || kind == PushKind.ALERT) PushKind.ALERT else PushKind.SILENT
        entry.pending = pending
        flushIfDue(entry, now)
    }

    /** Sends pending pushes whose window is up, and forgets idle tokens. */
    suspend fun tick() = mutex.withLock {
        val now = nowMillis()
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.pending != null) {
                flushIfDue(entry, now)
            } else if (now - entry.lastSentAt >= windowMs) {
                iterator.remove()
            }
        }
    }

    private fun flushIfDue(entry: Entry, now: Long) {
        val kind = entry.pending ?: return
        val due = if (kind == PushKind.ALERT) alertWindowMs else windowMs
        if (now - entry.lastSentAt < due) return
        entry.lastSentAt = now
        entry.pending = null
        send(Wake(entry.target, kind))
    }

    /** Drops any pending wake-up for a token that is gone. */
    suspend fun forget(token: String) {
        mutex.withLock { entries.remove(token) }
    }

    suspend fun trackedCount(): Int = mutex.withLock { entries.size }

    fun start(scope: CoroutineScope, interval: Duration = 1.seconds) = scope.launch {
        while (isActive) {
            delay(interval)
            tick()
        }
    }
}
