package com.yantagram.push.push

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The app's sender marker (event.ts senderMarker): the first 16 bytes of
 * HMAC-SHA256(K, utf8(content)), over the base64 content exactly as it appears in the
 * event. Only a device's own key reproduces it, so the push service can skip waking the
 * device that sent an event. The key is never logged.
 */
object SenderMarker {
    const val BYTES = 16

    fun compute(selfKey: ByteArray, content: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(selfKey, "HmacSHA256"))
        return mac.doFinal(content.toByteArray(Charsets.UTF_8)).copyOf(BYTES)
    }

    /** Whether the device holding [selfKey] sent the event. Constant-time comparison. */
    fun isFrom(selfKey: ByteArray, content: String, marker: ByteArray): Boolean =
        MessageDigest.isEqual(compute(selfKey, content), marker)
}
