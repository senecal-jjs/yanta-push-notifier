package com.yantagram.push.jobs

import com.yantagram.push.registry.Registry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

private val log = LoggerFactory.getLogger("com.yantagram.push.jobs.Expiry")

/**
 * Deletes registrations not refreshed within [ttl]: by then their tags have long stopped
 * matching anything. Rows are deleted, not archived (privacy rule 5). Runs at startup
 * and then daily.
 */
fun CoroutineScope.startExpiry(
    registry: Registry,
    ttl: Duration,
    interval: Duration = 1.days,
    nowMillis: () -> Long = System::currentTimeMillis,
) = launch {
    while (isActive) {
        try {
            val removed = registry.expire(nowMillis() - ttl.inWholeMilliseconds)
            if (removed > 0) log.info("expired {} registrations", removed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("expiry failed: {}", e.javaClass.simpleName)
        }
        delay(interval)
    }
}
