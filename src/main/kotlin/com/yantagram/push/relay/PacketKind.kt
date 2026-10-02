package com.yantagram.push.relay

import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Port of the app's services/packet-kind.ts: whether a relay packet is a chat message,
 * from its plaintext header alone. Only the packet type is read; nothing is decrypted.
 * Tested against the app's shared vectors (packet-kind-vectors.json).
 *
 * Packet header: version(1) type(1) hops(1) timestamp(8) flags(1) length(4), then
 * [originalSize(4) if compressed] and the payload. Fragment payload: setId(16) type(1)
 * index(2) total(2) next(16) data.
 */
object PacketKind {
    private const val HEADER_SIZE = 16
    private const val TYPE_MESSAGE = 4
    private const val TYPE_FRAGMENT = 8
    private const val FLAG_COMPRESSED = 0x01
    private const val FRAGMENT_TYPE_MESSAGE = 3
    private const val FRAGMENT_HEADER_SIZE = 37
    private const val MAX_MESSAGE_FRAGMENTS = 48
    private const val INFLATE_PREFIX = 64

    /** [content] is the event's base64 content. Malformed input is never a chat message. */
    fun isChatMessage(content: String): Boolean {
        val raw = try {
            Base64.getDecoder().decode(content)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return isChatMessage(raw)
    }

    fun isChatMessage(raw: ByteArray): Boolean {
        if (raw.size < HEADER_SIZE || raw.u8(0) != 1) return false
        val type = raw.u8(1)
        val length = raw.u32(12)
        if (HEADER_SIZE + length > raw.size) return false
        if (type == TYPE_MESSAGE) return true
        if (type != TYPE_FRAGMENT) return false

        if (raw.u8(11) and FLAG_COMPRESSED != 0) {
            if (length < 4) return false
            val originalSize = raw.u32(HEADER_SIZE)
            if (originalSize <= FRAGMENT_HEADER_SIZE) return false
            val head = inflatePrefix(raw, HEADER_SIZE + 4, length - 4) ?: return false
            // Only the header is inflated; the full size decides validity.
            return isFirstMessageFragment(head, originalSize)
        }
        val payload = raw.copyOfRange(HEADER_SIZE, HEADER_SIZE + length.toInt())
        return isFirstMessageFragment(payload, payload.size.toLong())
    }

    private fun isFirstMessageFragment(p: ByteArray, payloadSize: Long): Boolean {
        if (payloadSize <= FRAGMENT_HEADER_SIZE || p.size < 21) return false
        val index = p.u8(17) shl 8 or p.u8(18)
        val total = p.u8(19) shl 8 or p.u8(20)
        return p.u8(16) == FRAGMENT_TYPE_MESSAGE && index == 0 && total in 1..MAX_MESSAGE_FRAGMENTS
    }

    /** The first bytes of a zlib stream (with its header), capped at [INFLATE_PREFIX]. */
    private fun inflatePrefix(raw: ByteArray, offset: Int, length: Long): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(raw, offset, length.toInt())
            val out = ByteArray(INFLATE_PREFIX)
            var written = 0
            while (written < out.size && !inflater.finished()) {
                val n = inflater.inflate(out, written, out.size - written)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                written += n
            }
            if (written > 0) out.copyOf(written) else null
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xff

    private fun ByteArray.u32(at: Int): Long =
        (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()
}
