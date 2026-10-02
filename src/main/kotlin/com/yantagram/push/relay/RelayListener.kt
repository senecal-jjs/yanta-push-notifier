package com.yantagram.push.relay

import com.yantagram.push.Metrics
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Where the relay cursor survives restarts. */
interface CursorStore {
    fun load(): Long?
    fun save(cursor: Long)
}

/**
 * One subscription to strfry for every event of our kind. Each new event with a routing
 * tag goes to [onEvent]; matching against registrations happens there, in memory.
 */
class RelayListener(
    private val client: HttpClient,
    private val url: String,
    private val kind: Int,
    private val cursorStore: CursorStore,
    private val metrics: Metrics,
    private val onEvent: suspend (RelayEvent) -> Unit,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: Random = Random.Default,
) {
    private val log = LoggerFactory.getLogger(RelayListener::class.java)
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Newest `created_at` seen (never ahead of our own clock), in seconds. */
    private val newest = AtomicLong(0)
    private val seen = RecentIds(DEDUP_CAPACITY)

    /** Starts listening, persisting the cursor and watching for long disconnects. */
    fun start(scope: CoroutineScope) {
        newest.set(cursorStore.load() ?: nowSeconds())
        scope.launch { run() }
        scope.launch {
            while (isActive) {
                delay(CURSOR_SAVE_INTERVAL)
                saveCursor()
            }
        }
        scope.launch { watchDisconnects() }
    }

    fun saveCursor() {
        try {
            cursorStore.save(newest.get())
        } catch (e: Exception) {
            log.warn("cursor save failed: {}", e.javaClass.simpleName)
        }
    }

    /** The `since` for the next subscription: the cursor minus an overlap. */
    fun since(): Long = (newest.get() - OVERLAP.inWholeSeconds).coerceAtLeast(0)

    private suspend fun run() {
        var attempt = 0
        while (true) {
            try {
                client.webSocket(url) {
                    _connected.value = true
                    attempt = 0
                    log.info("relay connected")
                    send(Frame.Text(NostrMessages.req(SUBSCRIPTION_ID, kind, since())))
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        if (!handle(frame.readText())) break
                    }
                }
            } catch (e: CancellationException) {
                _connected.value = false
                throw e
            } catch (e: Exception) {
                // The first failure of an outage, then every tenth retry, so an outage isn't a flood.
                if (attempt % 10 == 0) {
                    log.warn("relay connection failed (attempt {}): {}: {}", attempt + 1, e.javaClass.simpleName, e.message)
                }
            }
            if (_connected.value) log.warn("relay disconnected")
            _connected.value = false
            metrics.relayReconnects.increment()
            delay(backoff(attempt++))
        }
    }

    /** Handles one relay message. Returns false when the subscription has ended. */
    internal suspend fun handle(text: String): Boolean {
        when (val message = NostrMessages.parse(text)) {
            is RelayMessage.Event -> {
                if (message.subscriptionId == SUBSCRIPTION_ID) onRelayEvent(message.event)
            }
            is RelayMessage.Closed -> {
                log.warn("relay closed the subscription")
                return false
            }
            is RelayMessage.Notice -> log.info("relay notice")
            // EOSE only marks the end of the backlog.
            is RelayMessage.Eose, RelayMessage.Other -> Unit
        }
        return true
    }

    private suspend fun onRelayEvent(event: RelayEvent) {
        if (event.kind != kind) return
        if (!seen.add(event.id)) return
        newest.updateAndGet { maxOf(it, minOf(event.createdAt, nowSeconds())) }
        metrics.eventsReceived.increment()
        if (event.routingTag != null) onEvent(event)
    }

    private suspend fun watchDisconnects() {
        var downFor = Duration.ZERO
        var alerted = false
        while (true) {
            delay(WATCH_INTERVAL)
            if (_connected.value) {
                downFor = Duration.ZERO
                alerted = false
            } else {
                downFor += WATCH_INTERVAL
                if (downFor >= DISCONNECT_ALERT && !alerted) {
                    log.error("ALERT relay disconnected for over {}", DISCONNECT_ALERT)
                    alerted = true
                }
            }
        }
    }

    /** Exponential from 1 s to 30 s, with jitter between half and all of it. */
    internal fun backoff(attempt: Int): Duration {
        val ceiling = (MIN_BACKOFF.inWholeMilliseconds shl attempt.coerceAtMost(10))
            .coerceAtMost(MAX_BACKOFF.inWholeMilliseconds)
        return (ceiling / 2 + random.nextLong(ceiling / 2 + 1)).milliseconds
    }

    companion object {
        const val SUBSCRIPTION_ID = "push"
        const val DEDUP_CAPACITY = 50_000
        val OVERLAP = 60.seconds
        val CURSOR_SAVE_INTERVAL = 1.minutes
        val MIN_BACKOFF = 1.seconds
        val MAX_BACKOFF = 30.seconds
        val DISCONNECT_ALERT = 5.minutes
        private val WATCH_INTERVAL = 10.seconds
    }
}

/** An LRU set of the most recent event IDs; reconnects replay an overlap. */
internal class RecentIds(private val capacity: Int) {
    private val ids = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = size > capacity
    }

    /** Returns false if [id] was already seen. */
    @Synchronized
    fun add(id: String): Boolean = ids.put(id, Unit) == null
}
