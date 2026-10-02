package com.yantagram.push.push

import kotlinx.coroutines.delay
import kotlin.time.Duration

/** One attempt's verdict. */
internal sealed interface Attempt {
    data class Done(val result: PushResult) : Attempt
    /** Transient (429, 5xx, network); [after] overrides the backoff when the provider asks. */
    data class Retry(val reason: String, val after: Duration? = null) : Attempt
}

/** Runs [attempt] up to [maxAttempts] times, backing off exponentially from [baseDelay]. */
internal suspend fun withRetries(
    maxAttempts: Int,
    baseDelay: Duration,
    maxDelay: Duration,
    attempt: suspend () -> Attempt,
): PushResult {
    var last: Attempt.Retry? = null
    for (n in 0 until maxAttempts) {
        if (last != null) delay((last.after ?: (baseDelay * (1 shl (n - 1)))).coerceAtMost(maxDelay))
        when (val outcome = attempt()) {
            is Attempt.Done -> return outcome.result
            is Attempt.Retry -> last = outcome
        }
    }
    return PushResult.Failed(last?.reason ?: "unknown")
}
