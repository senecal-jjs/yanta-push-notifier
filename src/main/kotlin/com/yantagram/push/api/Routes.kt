package com.yantagram.push.api

import com.yantagram.push.Metrics
import com.yantagram.push.registry.Registry
import com.yantagram.push.registry.RegistrationOptions
import com.yantagram.push.registry.ReplaceResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

val REGISTER_RATE_LIMIT = RateLimitName("register")

@Serializable
data class Health(val relay: String, val registrations: Int)

private val log = LoggerFactory.getLogger("com.yantagram.push.api")

fun Route.pushRoutes(
    registry: Registry,
    relayConnected: () -> Boolean,
    metrics: Metrics,
    clock: () -> Long = System::currentTimeMillis,
) {
    rateLimit(REGISTER_RATE_LIMIT) {
        route("/push/register") {
            put {
                when (val result = validate(call.receive<RegisterRequest>())) {
                    is Validated.Invalid -> call.respond(HttpStatusCode.BadRequest, result.reason)
                    Validated.TooManyTags -> call.respond(HttpStatusCode.PayloadTooLarge, "too many tags")
                    is Validated.Ok -> when (
                        registry.replace(
                            result.token, result.platform, result.tags, clock(),
                            RegistrationOptions(result.alerts, result.selfKey),
                        )
                    ) {
                        ReplaceResult.OK -> {
                            log.info("registration replaced ({} tags)", result.tags.size)
                            call.respond(HttpStatusCode.NoContent)
                        }
                        ReplaceResult.FULL -> {
                            log.error("ALERT registration cap reached; new registration refused")
                            metrics.registrationCapReached.increment()
                            call.respond(HttpStatusCode.ServiceUnavailable, "registrations full")
                        }
                    }
                }
            }
            // Always 204, so the response never reveals whether a token was registered.
            delete {
                val request = call.receive<UnregisterRequest>()
                if (registry.remove(normalizeToken(request.token))) log.info("registration removed")
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    get("/health") {
        val relayState = if (relayConnected()) "connected" else "reconnecting"
        call.respond(Health(relayState, registry.size))
    }
}
