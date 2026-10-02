package com.yantagram.push.push

import com.yantagram.push.Metrics
import com.yantagram.push.Platform
import com.yantagram.push.registry.Registry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * A bounded queue of wake-ups and a small pool of senders. When the queue is full the
 * oldest wake-ups are dropped: a missed wake-up only costs delay, since the app still
 * catches up when it is next opened.
 */
class PushDispatcher(
    private val senders: Map<Platform, PushSender>,
    private val registry: Registry,
    private val metrics: Metrics,
    capacity: Int = 10_000,
    private val onTokenRemoved: suspend (String) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(PushDispatcher::class.java)

    private val queue = Channel<Wake>(capacity, BufferOverflow.DROP_OLDEST) {
        metrics.dropped("queue_full")
    }

    /** Non-blocking; drops the oldest wake-up when full. */
    fun enqueue(wake: Wake) {
        queue.trySend(wake)
    }

    fun start(scope: CoroutineScope, workers: Int = 8) = repeat(workers) {
        scope.launch {
            for (wake in queue) deliver(wake)
        }
    }

    internal suspend fun deliver(wake: Wake) {
        val target = wake.target
        // It may have unregistered while the wake-up waited.
        if (!registry.contains(target.token)) return
        val sender = senders[target.platform] ?: return
        val result = try {
            sender.send(target.token, wake.kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PushResult.Failed("exception")
        }
        when (result) {
            PushResult.Sent -> metrics.sent(target.platform, wake.kind)
            is PushResult.InvalidToken -> {
                metrics.failed(target.platform, result.reason)
                if (registry.remove(target.token)) {
                    log.info("{} {}, token removed", target.platform.wire, result.reason)
                }
                onTokenRemoved(target.token)
            }
            is PushResult.Failed -> {
                metrics.failed(target.platform, result.reason)
                log.warn("{} push failed: {}", target.platform.wire, result.reason)
            }
        }
    }

    fun close() {
        queue.close()
    }
}
