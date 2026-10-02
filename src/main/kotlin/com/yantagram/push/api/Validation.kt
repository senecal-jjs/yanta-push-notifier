package com.yantagram.push.api

import com.yantagram.push.Platform
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.Serializable
import java.util.HexFormat

const val MAX_BODY_BYTES = 64L * 1024
const val MAX_TAGS = 512

private val APNS_TOKEN = Regex("^[0-9a-fA-F]{64}$")
private val FCM_TOKEN = Regex("^[A-Za-z0-9_:-]{100,300}$")
private val TAG = Regex("^[0-9a-f]{32}$")
private val SELF_KEY = Regex("^[0-9a-f]{64}$")

@Serializable
data class RegisterRequest(
    val token: String,
    val platform: String,
    val tags: List<String>,
    /** Required: whether the user has notifications on. A JSON boolean, not "true". */
    @Serializable(with = StrictBoolean::class) val alerts: Boolean,
    /** The device's sender marker key K (32 bytes, hex). Never logged. */
    @SerialName("self") val selfKey: String? = null,
)

@Serializable
data class UnregisterRequest(val token: String)

/** Rejects quoted booleans, which kotlinx.serialization would otherwise accept. */
object StrictBoolean : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("StrictBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("expected a boolean")
        if (element.isString) throw SerializationException("expected a boolean")
        return element.booleanOrNull ?: throw SerializationException("expected a boolean")
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

sealed interface Validated {
    data class Ok(
        val token: String,
        val platform: Platform,
        val tags: Set<String>,
        val alerts: Boolean,
        val selfKey: ByteArray?,
    ) : Validated
    data object TooManyTags : Validated
    /** A fixed reason, never echoing the input. */
    data class Invalid(val reason: String) : Validated
}

fun isTag(value: String) = TAG.matches(value)

fun validate(request: RegisterRequest): Validated {
    val platform = Platform.fromWire(request.platform) ?: return Validated.Invalid("unknown platform")
    val token = when (platform) {
        // APNs tokens are hex; normalize the case so one device is one row.
        Platform.IOS -> request.token.takeIf { APNS_TOKEN.matches(it) }?.lowercase()
        Platform.ANDROID -> request.token.takeIf { FCM_TOKEN.matches(it) }
    } ?: return Validated.Invalid("bad token")
    if (request.tags.size > MAX_TAGS) return Validated.TooManyTags
    if (!request.tags.all(::isTag)) return Validated.Invalid("bad tag")
    val selfKey = request.selfKey?.let { key ->
        if (!SELF_KEY.matches(key)) return Validated.Invalid("bad self")
        HexFormat.of().parseHex(key)
    }
    return Validated.Ok(token, platform, request.tags.toSet(), request.alerts, selfKey)
}

/** The key a token is stored under, whatever platform it claims to be. */
fun normalizeToken(token: String): String = if (APNS_TOKEN.matches(token)) token.lowercase() else token
