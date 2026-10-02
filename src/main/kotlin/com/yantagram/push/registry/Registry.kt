package com.yantagram.push.registry

import com.yantagram.push.Platform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger

/** A device to wake. */
data class Target(val token: String, val platform: Platform)

/** A registered device whose tags matched an event. */
class Match(
    val target: Target,
    /** The user has notifications on. */
    val alerts: Boolean,
    /** The device's sender marker key K, or null. Never logged. */
    val selfKey: ByteArray?,
)

/** What a registration carries besides its tags. */
data class RegistrationOptions(val alerts: Boolean = false, val selfKey: ByteArray? = null) {
    override fun equals(other: Any?) =
        other is RegistrationOptions && alerts == other.alerts && selfKey.contentEquals(other.selfKey)

    override fun hashCode() = 31 * alerts.hashCode() + selfKey.contentHashCode()

    /** Never prints the key. */
    override fun toString() = "RegistrationOptions(alerts=$alerts, selfKey=${if (selfKey == null) "none" else "set"})"
}

private class Registration(
    val platform: Platform,
    val tags: Set<String>,
    val updatedAt: Long,
    val alerts: Boolean,
    val selfKey: ByteArray?,
)

enum class ReplaceResult { OK, FULL }

/**
 * The in-memory index (tag → tokens, token → registration), written through to SQLite
 * on every change. All changes happen under one mutex, store first, so memory never
 * holds something the store failed to save.
 */
class Registry(
    private val store: Store,
    private val maxRegistrations: Int,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val byTag = HashMap<String, MutableSet<String>>()
    private val byToken = HashMap<String, Registration>()
    private val count = AtomicInteger(0)
    private val hex = HexFormat.of()

    val size: Int get() = count.get()

    suspend fun load() = mutex.withLock {
        val rows = withContext(io) { store.loadAll() }
        byTag.clear()
        byToken.clear()
        for (row in rows) {
            val selfKey = row.selfKey?.let { runCatching { hex.parseHex(it) }.getOrNull() }
            index(row.token, Registration(row.platform, row.tags, row.updatedAt, row.alerts, selfKey))
        }
        count.set(byToken.size)
    }

    /**
     * Replaces the token's whole registration (tags, alerts, sender key) in one step; an
     * empty tag set unregisters it. Saving the same set again refreshes `updatedAt`.
     */
    suspend fun replace(
        token: String,
        platform: Platform,
        tags: Set<String>,
        now: Long,
        options: RegistrationOptions = RegistrationOptions(),
    ): ReplaceResult {
        if (tags.isEmpty()) {
            remove(token)
            return ReplaceResult.OK
        }
        return mutex.withLock {
            if (token !in byToken && byToken.size >= maxRegistrations) return@withLock ReplaceResult.FULL
            val registration = Registration(platform, tags.toSet(), now, options.alerts, options.selfKey?.copyOf())
            withContext(io) {
                store.upsert(
                    StoredRegistration(
                        token, platform, registration.tags, now,
                        alerts = registration.alerts,
                        selfKey = registration.selfKey?.let(hex::formatHex),
                    ),
                )
            }
            unindex(token)
            index(token, registration)
            count.set(byToken.size)
            ReplaceResult.OK
        }
    }

    /** Removes the registration, sender key included. */
    suspend fun remove(token: String): Boolean = mutex.withLock {
        if (token !in byToken) return@withLock false
        withContext(io) { store.delete(token) }
        unindex(token)
        count.set(byToken.size)
        true
    }

    suspend fun match(tag: String): List<Match> = mutex.withLock {
        byTag[tag]?.mapNotNull { token ->
            byToken[token]?.let { Match(Target(token, it.platform), it.alerts, it.selfKey) }
        }.orEmpty()
    }

    suspend fun contains(token: String): Boolean = mutex.withLock { token in byToken }

    suspend fun tagsOf(token: String): Set<String>? = mutex.withLock { byToken[token]?.tags }

    suspend fun optionsOf(token: String): RegistrationOptions? =
        mutex.withLock { byToken[token]?.let { RegistrationOptions(it.alerts, it.selfKey?.copyOf()) } }

    /** Removes registrations not refreshed since [cutoff]. Returns how many. */
    suspend fun expire(cutoff: Long): Int = mutex.withLock {
        val stale = byToken.filterValues { it.updatedAt < cutoff }.keys.toList()
        if (stale.isEmpty()) return@withLock 0
        withContext(io) { store.deleteAll(stale) }
        stale.forEach(::unindex)
        count.set(byToken.size)
        stale.size
    }

    private fun index(token: String, registration: Registration) {
        byToken[token] = registration
        for (tag in registration.tags) byTag.getOrPut(tag) { HashSet() }.add(token)
    }

    private fun unindex(token: String) {
        val old = byToken.remove(token) ?: return
        for (tag in old.tags) {
            val tokens = byTag[tag] ?: continue
            tokens.remove(token)
            if (tokens.isEmpty()) byTag.remove(tag)
        }
    }

    /** For tests: the tokens indexed under [tag]. */
    internal suspend fun tokensFor(tag: String): Set<String> = mutex.withLock { byTag[tag]?.toSet().orEmpty() }
}
