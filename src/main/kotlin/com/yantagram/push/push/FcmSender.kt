package com.yantagram.push.push

import com.google.auth.oauth2.ServiceAccountCredentials
import com.yantagram.push.FcmConfig
import com.yantagram.push.Platform
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** OAuth2 access tokens for the FCM API; a seam for tests. */
fun interface AccessTokenProvider {
    suspend fun accessToken(): String
}

class ServiceAccountTokenProvider(config: FcmConfig) : AccessTokenProvider {
    private val credentials = ServiceAccountCredentials.newBuilder()
        .setClientEmail(config.clientEmail)
        .setPrivateKeyString(config.privateKey)
        .setScopes(listOf(SCOPE))
        .build()

    /** Cached by the library until shortly before it expires. */
    override suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        credentials.refreshIfExpired()
        checkNotNull(credentials.accessToken) { "no FCM access token" }.tokenValue
    }

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    }
}

/** High-priority data-only messages over the FCM HTTP v1 API. */
class FcmSender(
    private val http: HttpClient,
    private val projectId: String,
    private val tokens: AccessTokenProvider,
    private val baseUrl: String = "https://fcm.googleapis.com",
    private val baseDelay: Duration = 1.seconds,
) : PushSender {
    override val platform = Platform.ANDROID

    private val json = Json { ignoreUnknownKeys = true }

    /** Exactly the message in the spec: `{"w":"1"}`, high priority, one hour, collapse "wake". */
    fun message(token: String): JsonObject = buildJsonObject {
        putJsonObject("message") {
            put("token", token)
            putJsonObject("data") { put("w", "1") }
            putJsonObject("android") {
                put("priority", "HIGH")
                put("ttl", "3600s")
                put("collapse_key", "wake")
            }
        }
    }

    /** Android always gets the data message: the app decides what to show. */
    override suspend fun send(token: String, kind: PushKind): PushResult = withRetries(MAX_ATTEMPTS, baseDelay, 30.seconds) {
        val response = try {
            http.post("$baseUrl/v1/projects/$projectId/messages:send") {
                bearerAuth(tokens.accessToken())
                contentType(ContentType.Application.Json)
                setBody(message(token).toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withRetries Attempt.Retry("fcm_network")
        }
        val status = response.status.value
        if (status in 200..299) return@withRetries Attempt.Done(PushResult.Sent)

        val error = parseError(response.bodyAsText())
        when {
            status == 404 || error.errorCode == "UNREGISTERED" ->
                Attempt.Done(PushResult.InvalidToken("fcm_unregistered"))
            status == 400 && error.isAboutToken() ->
                Attempt.Done(PushResult.InvalidToken("fcm_invalid_token"))
            status == 429 || status >= 500 -> Attempt.Retry(
                "fcm_$status",
                response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds,
            )
            else -> Attempt.Done(PushResult.Failed("fcm_$status"))
        }
    }

    private data class FcmError(val status: String?, val errorCode: String?, val message: String?) {
        /** `INVALID_ARGUMENT` about the registration token, not about our own message. */
        fun isAboutToken() = (status == "INVALID_ARGUMENT" || errorCode == "INVALID_ARGUMENT") &&
            message?.contains("token", ignoreCase = true) == true
    }

    private fun parseError(body: String): FcmError = try {
        val error = json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject
        val errorCode = (error["details"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("errorCode")?.jsonPrimitive?.contentOrNull }
            .firstOrNull()
        FcmError(
            status = error["status"]?.jsonPrimitive?.contentOrNull,
            errorCode = errorCode,
            message = error["message"]?.jsonPrimitive?.contentOrNull,
        )
    } catch (_: Exception) {
        FcmError(null, null, null)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
