package com.yantagram.push.push

import com.yantagram.push.Platform
import com.yantagram.push.registry.Target

/** Silent wakes the app; alert also shows a generic banner the iOS extension rewrites. */
enum class PushKind(val label: String) {
    SILENT("silent"),
    ALERT("alert"),
}

/** One push to send. */
data class Wake(val target: Target, val kind: PushKind)

/** The outcome of one push, the same for both providers. */
sealed interface PushResult {
    data object Sent : PushResult
    /** The provider says the token is dead: remove the registration. */
    data class InvalidToken(val reason: String) : PushResult
    /** Gave up; [reason] is a short label for metrics, never user data. */
    data class Failed(val reason: String) : PushResult
}

interface PushSender {
    val platform: Platform

    /** Sends one push of [kind], retrying transient failures itself. */
    suspend fun send(token: String, kind: PushKind): PushResult

    suspend fun close() {}
}
