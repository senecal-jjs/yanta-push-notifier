package com.yantagram.push.relay

import com.yantagram.push.Metrics
import com.yantagram.push.tag
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Collections
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class MemoryCursorStore(var cursor: Long? = null) : CursorStore {
    override fun load() = cursor
    override fun save(cursor: Long) {
        this.cursor = cursor
    }
}

fun eventJson(id: String, createdAt: Long, tag: String?, kind: Int = 2929): String {
    val tags = buildList {
        if (tag != null) add("""["y","$tag"]""")
        add("""["expiration","${createdAt + 3600}"]""")
    }.joinToString(",")
    return """{"id":"$id","pubkey":"${"b".repeat(64)}","created_at":$createdAt,"kind":$kind,""" +
        """"tags":[$tags],"content":"secret","sig":"${"c".repeat(128)}"}"""
}

fun eventMessage(id: String, createdAt: Long, tag: String?, kind: Int = 2929) =
    """["EVENT","push",${eventJson(id, createdAt, tag, kind)}]"""

/** A scope whose coroutines never run, so start() only loads the cursor. */
private fun stoppedScope() = CoroutineScope(SupervisorJob()).also { it.cancel() }

class RelayListenerTest {
    private val received = mutableListOf<String>()
    private val metrics = Metrics()
    private var now = 1_000_000L

    private fun listener(store: CursorStore = MemoryCursorStore(), client: HttpClient = HttpClient(MockEngine { respondOk() }), url: String = "ws://unused") =
        RelayListener(client, url, 2929, store, metrics, { received += it.routingTag!! }, { now }, Random(1))

    @Test
    fun `parses REQ and relay messages`() {
        assertEquals("""["REQ","push",{"kinds":[2929],"since":5}]""", NostrMessages.req("push", 2929, 5))
        val event = NostrMessages.parse(eventMessage("e1", 10, tag(1))) as RelayMessage.Event
        assertEquals(RelayEvent("e1", 10, 2929, tag(1), senderMarker = null, content = "secret"), event.event)
        assertEquals(RelayMessage.Eose("push"), NostrMessages.parse("""["EOSE","push"]"""))
        assertEquals(RelayMessage.Closed("push", "error: x"), NostrMessages.parse("""["CLOSED","push","error: x"]"""))
        assertEquals(RelayMessage.Other, NostrMessages.parse("garbage"))
        val marked = """["EVENT","push",{"id":"e","created_at":1,"kind":2929,"content":"AQID",""" +
            """"tags":[["y","${tag(1)}"],["s","${tag(7)}"]]}]"""
        assertEquals(RelayEvent("e", 1, 2929, tag(1), tag(7), "AQID"), (NostrMessages.parse(marked) as RelayMessage.Event).event)
        val twoMarkers = """["EVENT","push",{"id":"e","created_at":1,"kind":2929,"content":"AQID",""" +
            """"tags":[["y","${tag(1)}"],["s","${tag(7)}"],["s","${tag(8)}"]]}]"""
        assertEquals(null, (NostrMessages.parse(twoMarkers) as RelayMessage.Event).event.senderMarker)
        // Two routing tags is not well formed: no routing tag.
        val two = """["EVENT","push",{"id":"e","created_at":1,"kind":2929,"tags":[["y","${tag(1)}"],["y","${tag(2)}"]]}]"""
        assertEquals(null, (NostrMessages.parse(two) as RelayMessage.Event).event.routingTag)
    }

    @Test
    fun `hands each event's routing tag on, once`() = runTest {
        val relay = listener()
        relay.handle(eventMessage("e1", now, tag(1)))
        relay.handle(eventMessage("e2", now, tag(2)))
        relay.handle(eventMessage("e1", now, tag(1)))
        relay.handle(eventMessage("e3", now, tag(3), kind = 1))
        assertEquals(listOf(tag(1), tag(2)), received)
        assertEquals(2.0, metrics.eventsReceived.count())
    }

    @Test
    fun `CLOSED ends the subscription`() = runTest {
        assertFalse(listener().handle("""["CLOSED","push","shutting down"]"""))
        assertTrue(listener().handle("""["EOSE","push"]"""))
    }

    @Test
    fun `the cursor is the newest created_at minus 60 seconds, never ahead of the clock`() = runTest {
        val relay = listener()
        relay.handle(eventMessage("e1", now - 500, tag(1)))
        relay.handle(eventMessage("e2", now - 100, tag(1)))
        relay.handle(eventMessage("e3", now - 300, tag(1)))
        assertEquals(now - 160, relay.since())
        relay.handle(eventMessage("e4", now + 86_000, tag(1)))
        assertEquals(now - 60, relay.since())
    }

    @Test
    fun `the cursor resumes after a restart`() = runTest {
        val store = MemoryCursorStore()
        val first = listener(store)
        first.start(stoppedScope())
        now += 5_000
        first.handle(eventMessage("e1", now - 10, tag(1)))
        first.saveCursor()

        now += 3_600
        val second = listener(store)
        second.start(stoppedScope())
        assertEquals(now - 3_600 - 10 - 60, second.since())
    }

    @Test
    fun `first start begins now`() {
        val relay = listener()
        relay.start(stoppedScope())
        assertEquals(now - 60, relay.since())
    }

    @Test
    fun `backoff grows from 1 to 30 seconds with jitter`() {
        val relay = listener()
        for (attempt in 0..20) {
            val ceiling = minOf(1L shl minOf(attempt, 10), 30L).seconds
            val delay = relay.backoff(attempt)
            assertTrue(delay <= ceiling && delay >= ceiling / 2, "attempt $attempt: $delay")
        }
    }

    @Test
    fun `reconnects, resubscribes from the cursor and drops replayed events`() = runBlocking {
        val reqs = Collections.synchronizedList(mutableListOf<String>())
        var connections = 0
        val base = System.currentTimeMillis() / 1000
        val server = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    reqs += (incoming.receive() as Frame.Text).readText()
                    when (connections++) {
                        0 -> {
                            send(Frame.Text(eventMessage("e1", base - 10, tag(1))))
                            send(Frame.Text(eventMessage("e2", base - 5, tag(2))))
                            send(Frame.Text("""["EOSE","push"]"""))
                            // Drop the connection.
                        }
                        else -> {
                            send(Frame.Text(eventMessage("e2", base - 5, tag(2))))
                            send(Frame.Text(eventMessage("e3", base, tag(3))))
                            send(Frame.Text("""["EOSE","push"]"""))
                            for (frame in incoming) Unit
                        }
                    }
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { install(ClientWebSockets) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val synced = Collections.synchronizedList(received)
        try {
            val relay = RelayListener(client, "ws://127.0.0.1:$port/", 2929, MemoryCursorStore(base - 100), metrics, { synced += it.routingTag!! })
            relay.start(scope)
            withTimeout(10.seconds) { while (synced.size < 3) delay(20) }
            delay(200)

            assertEquals(listOf(tag(1), tag(2), tag(3)), synced.toList())
            assertEquals(2, reqs.size)
            fun since(req: String) = Json.parseToJsonElement(req).jsonArray[2].jsonObject["since"]!!.jsonPrimitive.long
            assertEquals(base - 160, since(reqs[0]))
            assertEquals(base - 65, since(reqs[1]))
            assertTrue(relay.connected.value)
            assertEquals(1.0, metrics.relayReconnects.count())
        } finally {
            scope.cancel()
            client.close()
            server.stop(0, 0)
        }
    }
}
