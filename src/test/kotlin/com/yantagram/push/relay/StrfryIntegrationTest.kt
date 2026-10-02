package com.yantagram.push.relay

import com.yantagram.push.Metrics
import com.yantagram.push.Platform
import com.yantagram.push.apnsToken
import com.yantagram.push.newRegistry
import com.yantagram.push.push.Coalescer
import com.yantagram.push.push.Matcher
import com.yantagram.push.push.PushDispatcher
import com.yantagram.push.push.FakeSender
import com.yantagram.push.tag
import fr.acinq.secp256k1.Secp256k1
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.security.MessageDigest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a real strfry, publishes signed kind-2929 events shaped like nostr-tools output,
 * and checks what reaches a fake sender. Skipped when Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
class StrfryIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val strfry: GenericContainer<*> = GenericContainer("dockurr/strfry:latest")
            .withCopyFileToContainer(MountableFile.forClasspathResource("strfry.conf"), "/etc/strfry.conf")
            .withExposedPorts(7777)
            .waitingFor(Wait.forListeningPort())
    }

    private val secretKey = ByteArray(32) { (it + 1).toByte() }
    private val pubkey = Secp256k1.pubkeyCreate(secretKey).copyOfRange(1, 33).hex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** A signed event as nostr-tools' finalizeEvent would produce it. */
    private fun signedEvent(tag: String, createdAt: Long, content: String): String {
        val tags = JsonArray(
            listOf(
                JsonArray(listOf(JsonPrimitive("y"), JsonPrimitive(tag))),
                JsonArray(listOf(JsonPrimitive("expiration"), JsonPrimitive((createdAt + 3600).toString()))),
            ),
        )
        val serialized = buildJsonArray {
            add(JsonPrimitive(0)); add(JsonPrimitive(pubkey)); add(JsonPrimitive(createdAt))
            add(JsonPrimitive(2929)); add(tags); add(JsonPrimitive(content))
        }.toString()
        val id = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray())
        val sig = Secp256k1.signSchnorr(id, secretKey, null)
        return buildJsonObject {
            put("id", id.hex()); put("pubkey", pubkey); put("created_at", createdAt); put("kind", 2929)
            put("tags", tags); put("content", content); put("sig", sig.hex())
        }.toString()
    }

    @Test
    fun `events on registered tags wake their devices through strfry`() = runBlocking {
        val url = "ws://${strfry.host}:${strfry.getMappedPort(7777)}"
        val client = HttpClient(CIO) { install(WebSockets) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val metrics = Metrics()
            val registry = newRegistry()
            registry.replace(apnsToken(1), Platform.IOS, setOf(tag(1)), 0)
            registry.replace(apnsToken(2), Platform.IOS, setOf(tag(2)), 0)
            val sender = FakeSender(Platform.IOS)
            val sent = Collections.synchronizedList(sender.sent)
            val dispatcher = PushDispatcher(mapOf(Platform.IOS to sender), registry, metrics)
            val coalescer = Coalescer(30.seconds, dispatcher::enqueue)
            val relay = RelayListener(client, url, 2929, MemoryCursorStore(), metrics, Matcher(registry, coalescer, metrics)::onEvent)
            dispatcher.start(scope, workers = 1)
            relay.start(scope)
            withTimeout(10.seconds) { while (!relay.connected.value) delay(20) }

            val now = System.currentTimeMillis() / 1000
            client.webSocket(url) {
                // Five fragments for device 1, one for an unregistered tag.
                val events = (1..5).map { signedEvent(tag(1), now, "fragment $it") } + signedEvent(tag(9), now, "x")
                for (event in events) {
                    send(Frame.Text("""["EVENT",$event]"""))
                    val ok = (incoming.receive() as Frame.Text).readText()
                    assertTrue(ok.startsWith("[\"OK\"") && ok.contains("true"), ok)
                }
            }

            withTimeout(10.seconds) { while (metrics.eventsReceived.count() < 6) delay(20) }
            delay(300)
            assertEquals(listOf(apnsToken(1)), sent.toList())
            assertEquals(5.0, metrics.eventsMatched.count())
        } finally {
            scope.cancel()
            client.close()
        }
    }
}
