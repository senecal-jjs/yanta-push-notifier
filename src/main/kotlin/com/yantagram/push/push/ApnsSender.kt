package com.yantagram.push.push

import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.ApnsClientBuilder
import com.eatthepath.pushy.apns.DeliveryPriority
import com.eatthepath.pushy.apns.PushType
import com.eatthepath.pushy.apns.auth.ApnsSigningKey
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification
import com.yantagram.push.ApnsConfig
import com.yantagram.push.ApnsEnvironment
import com.yantagram.push.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.Duration.Companion.hours

/** An APNs response: the HTTP status and Apple's reason, if rejected. */
data class ApnsResponse(val statusCode: Int, val rejectionReason: String?)

/** The HTTP/2 connection to APNs; a seam for tests. */
interface ApnsGateway {
    suspend fun send(notification: SimpleApnsPushNotification): ApnsResponse
    suspend fun close() {}
}

class PushyApnsGateway(private val client: ApnsClient) : ApnsGateway {
    override suspend fun send(notification: SimpleApnsPushNotification): ApnsResponse {
        val response = client.sendNotification(notification).await()
        return ApnsResponse(
            statusCode = if (response.isAccepted) 200 else response.statusCode,
            rejectionReason = response.rejectionReason.orElse(null),
        )
    }

    override suspend fun close() {
        client.close().await()
    }

    companion object {
        fun create(config: ApnsConfig): PushyApnsGateway {
            val key = ApnsSigningKey.loadFromInputStream(config.keyP8.byteInputStream(), config.teamId, config.keyId)
            val host = when (config.environment) {
                ApnsEnvironment.PRODUCTION -> ApnsClientBuilder.PRODUCTION_APNS_HOST
                ApnsEnvironment.SANDBOX -> ApnsClientBuilder.DEVELOPMENT_APNS_HOST
            }
            val client = ApnsClientBuilder()
                .setApnsServer(host)
                .setSigningKey(key)
                .setConnectionTimeout(10.seconds.toJavaDuration())
                .build()
            return PushyApnsGateway(client)
        }
    }
}

/** Silent background pushes and generic alerts over APNs (token auth). */
class ApnsSender(
    private val gateway: ApnsGateway,
    private val topic: String,
    private val now: () -> Instant = Instant::now,
    private val baseDelay: Duration = 1.seconds,
) : PushSender {
    override val platform = Platform.IOS

    /** Exactly the pushes in the spec. No tags, IDs or group information in either. */
    fun notification(token: String, kind: PushKind = PushKind.SILENT): SimpleApnsPushNotification = when (kind) {
        // Background, priority 5, one hour, collapse "wake".
        PushKind.SILENT -> SimpleApnsPushNotification(
            token,
            topic,
            SILENT_PAYLOAD,
            now().plusSeconds(SILENT_EXPIRATION.inWholeSeconds),
            DeliveryPriority.CONSERVE_POWER,
            PushType.BACKGROUND,
            COLLAPSE_ID,
        )
        // Alert, priority 10, 24 hours, no collapse id: a collapsed alert would replace an
        // earlier group's banner, and the extension won't announce those messages again.
        PushKind.ALERT -> SimpleApnsPushNotification(
            token,
            topic,
            ALERT_PAYLOAD,
            now().plusSeconds(ALERT_EXPIRATION.inWholeSeconds),
            DeliveryPriority.IMMEDIATE,
            PushType.ALERT,
        )
    }

    override suspend fun send(token: String, kind: PushKind): PushResult = withRetries(MAX_ATTEMPTS, baseDelay, 30.seconds) {
        val response = try {
            gateway.send(notification(token, kind))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withRetries Attempt.Retry("apns_network")
        }
        when {
            response.statusCode == 200 -> Attempt.Done(PushResult.Sent)
            response.statusCode == 410 -> Attempt.Done(PushResult.InvalidToken("apns_410"))
            response.statusCode == 400 && response.rejectionReason == "BadDeviceToken" ->
                Attempt.Done(PushResult.InvalidToken("apns_bad_device_token"))
            response.statusCode == 429 || response.statusCode >= 500 -> Attempt.Retry("apns_${response.statusCode}")
            // Apple's reasons are a fixed set (InvalidProviderToken, TopicDisallowed, ...), safe to log.
            else -> Attempt.Done(PushResult.Failed(listOfNotNull("apns_${response.statusCode}", response.rejectionReason).joinToString("_")))
        }
    }

    override suspend fun close() = gateway.close()

    companion object {
        const val SILENT_PAYLOAD = """{"aps":{"content-available":1}}"""

        /**
         * mutable-content runs the notification extension, which replaces the title and
         * body; the generic text shows if it can't. content-available also wakes the app.
         */
        const val ALERT_PAYLOAD = """{"aps":{"alert":{"title":"Yantagram","body":"New message"},""" +
            """"sound":"default","mutable-content":1,"content-available":1}}"""
        const val COLLAPSE_ID = "wake"
        val SILENT_EXPIRATION = 1.hours
        val ALERT_EXPIRATION = 24.hours
        const val MAX_ATTEMPTS = 3
    }
}
