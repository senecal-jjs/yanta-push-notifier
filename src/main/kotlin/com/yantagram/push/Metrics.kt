package com.yantagram.push

import com.yantagram.push.push.PushKind
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

/**
 * Counts only (privacy rule 2): no token, tag or address ever becomes a label.
 * Labels are limited to fixed sets: platform and a short failure reason.
 */
class Metrics(val registry: MeterRegistry = SimpleMeterRegistry()) {
    val eventsReceived: Counter = Counter.builder("push_events_received").register(registry)
    val eventsMatched: Counter = Counter.builder("push_events_matched").register(registry)
    val relayReconnects: Counter = Counter.builder("push_relay_reconnects").register(registry)
    val registrationCapReached: Counter = Counter.builder("push_registration_cap_reached").register(registry)

    /** Pushes sent, by platform and type (alert or silent). */
    fun sent(platform: Platform, kind: PushKind) =
        Counter.builder("push_sent").tag("platform", platform.wire).tag("type", kind.label)
            .register(registry).increment()

    /** Devices not pushed because they sent the event themselves. */
    fun selfSkipped(platform: Platform) = counter("push_self_skipped", "platform", platform.wire).increment()

    fun dropped(reason: String) = counter("push_dropped", "reason", reason).increment()

    fun failed(platform: Platform, reason: String) =
        Counter.builder("push_failed").tag("platform", platform.wire).tag("reason", reason)
            .register(registry).increment()

    private fun counter(name: String, tag: String, value: String) =
        Counter.builder(name).tag(tag, value).register(registry)
}
