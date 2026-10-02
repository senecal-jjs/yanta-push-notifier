package com.yantagram.push.push

import com.yantagram.push.Metrics
import com.yantagram.push.Platform
import com.yantagram.push.registry.Registry
import com.yantagram.push.relay.PacketKind
import com.yantagram.push.relay.RelayEvent
import java.util.HexFormat

/**
 * Routing tag → registered devices → coalesced pushes. Most events match nothing.
 *
 * The device that sent an event (its sender marker matches) gets no push at all. Others
 * get an alert when the event is a chat message and they're an iOS device with
 * notifications on, and a silent push otherwise.
 */
class Matcher(
    private val registry: Registry,
    private val coalescer: Coalescer,
    private val metrics: Metrics,
) {
    suspend fun onEvent(event: RelayEvent) {
        val tag = event.routingTag ?: return
        val matches = registry.match(tag)
        if (matches.isEmpty()) return
        metrics.eventsMatched.increment()

        // Without an s tag the event matches no device as its sender.
        val marker = event.senderMarker?.let { HexFormat.of().parseHex(it) }
        val chat by lazy { PacketKind.isChatMessage(event.content) }

        for (match in matches) {
            val target = match.target
            val selfKey = match.selfKey
            if (marker != null && selfKey != null && SenderMarker.isFrom(selfKey, event.content, marker)) {
                metrics.selfSkipped(target.platform)
                continue
            }
            val alert = target.platform == Platform.IOS && match.alerts && chat
            coalescer.wake(target, if (alert) PushKind.ALERT else PushKind.SILENT)
        }
    }
}
