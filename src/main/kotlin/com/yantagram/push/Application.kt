package com.yantagram.push

import com.yantagram.push.api.MAX_BODY_BYTES
import com.yantagram.push.api.REGISTER_RATE_LIMIT
import com.yantagram.push.api.pushRoutes
import com.yantagram.push.jobs.startExpiry
import com.yantagram.push.push.ApnsSender
import com.yantagram.push.push.Coalescer
import com.yantagram.push.push.FcmSender
import com.yantagram.push.push.Matcher
import com.yantagram.push.push.PushDispatcher
import com.yantagram.push.push.PushyApnsGateway
import com.yantagram.push.push.ServiceAccountTokenProvider
import com.yantagram.push.registry.Registry
import com.yantagram.push.registry.SqliteCursorStore
import com.yantagram.push.registry.Store
import com.yantagram.push.relay.RelayListener
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Gauge
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("com.yantagram.push.Application")

fun main() {
    val config = Config.fromEnv()
    val prometheus = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val metrics = Metrics(prometheus)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store = Store(config.dbPath)
    val registry = Registry(store, config.maxRegistrations)
    runBlocking { registry.load() }
    log.info("loaded {} registrations", registry.size)

    val http = HttpClient(CIO) {
        install(WebSockets) { pingIntervalMillis = 20.seconds.inWholeMilliseconds }
        install(HttpTimeout) { connectTimeoutMillis = 10.seconds.inWholeMilliseconds }
    }
    val senders = listOf(
        ApnsSender(PushyApnsGateway.create(config.apns), config.apns.topic),
        FcmSender(http, config.fcm.projectId, ServiceAccountTokenProvider(config.fcm)),
    ).associateBy { it.platform }

    lateinit var coalescer: Coalescer
    val dispatcher = PushDispatcher(senders, registry, metrics, onTokenRemoved = { coalescer.forget(it) })
    coalescer = Coalescer(config.coalesceWindow, dispatcher::enqueue, alertWindow = config.alertCoalesceWindow)
    val matcher = Matcher(registry, coalescer, metrics)
    val relay = RelayListener(
        http, config.strfryUrl, config.eventKind, SqliteCursorStore(store), metrics, matcher::onEvent,
    )

    Gauge.builder("push_registrations") { registry.size }.register(prometheus)
    Gauge.builder("push_relay_connected") { if (relay.connected.value) 1 else 0 }.register(prometheus)

    dispatcher.start(scope)
    coalescer.start(scope)
    relay.start(scope)
    scope.startExpiry(registry, config.registrationTtl)

    // Metrics on their own port, never routed publicly (see fly.toml).
    embeddedServer(Netty, port = config.metricsPort) {
        routing {
            get("/metrics") { call.respondText(prometheus.scrape(), ContentType.Text.Plain) }
        }
    }.start(wait = false)

    embeddedServer(Netty, port = config.port) {
        pushModule(registry, { relay.connected.value }, metrics)
        monitor.subscribe(ApplicationStopped) {
            relay.saveCursor()
            dispatcher.close()
            runBlocking { senders.values.forEach { it.close() } }
            http.close()
            scope.cancel()
        }
    }.start(wait = true)
}

/** Plugins and routes. Deliberately no CallLogging: logs never hold IPs, tokens or tags. */
fun Application.pushModule(
    registry: Registry,
    relayConnected: () -> Boolean,
    metrics: Metrics,
    registrationsPerMinute: Int = 30,
    clock: () -> Long = System::currentTimeMillis,
) {
    install(ContentNegotiation) { json() }
    install(RequestBodyLimit) { bodyLimit { MAX_BODY_BYTES } }
    install(RateLimit) {
        register(REGISTER_RATE_LIMIT) {
            rateLimiter(limit = registrationsPerMinute, refillPeriod = 1.minutes)
            // Fly's edge sets Fly-Client-IP. The counters are in memory and expire on refill.
            requestKey { call -> call.request.header("Fly-Client-IP") ?: call.request.origin.remoteAddress }
        }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            when {
                cause.causes().any { it is PayloadTooLargeException } ->
                    call.respond(HttpStatusCode.PayloadTooLarge, "body too large")
                cause is BadRequestException -> call.respond(HttpStatusCode.BadRequest, "malformed body")
                else -> {
                    // The class only: messages can quote request bodies.
                    log.error("request failed: {}", cause.javaClass.name)
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
        }
    }
    routing { pushRoutes(registry, relayConnected, metrics, clock) }
}

private fun Throwable.causes() = generateSequence(this) { it.cause }
