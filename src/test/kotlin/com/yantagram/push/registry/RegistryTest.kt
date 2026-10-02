package com.yantagram.push.registry

import com.yantagram.push.Platform
import com.yantagram.push.apnsToken
import com.yantagram.push.newRegistry
import com.yantagram.push.tag
import com.yantagram.push.tempDbPath
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryTest {
    private val token = apnsToken(1)

    @Test
    fun `replacing a token's tags updates both maps`() = runTest {
        val registry = newRegistry()
        registry.replace(token, Platform.IOS, setOf(tag(1), tag(2)), now = 1)
        registry.replace(token, Platform.IOS, setOf(tag(2), tag(3)), now = 2)

        assertEquals(setOf(tag(2), tag(3)), registry.tagsOf(token))
        assertEquals(emptySet(), registry.tokensFor(tag(1)))
        assertEquals(setOf(token), registry.tokensFor(tag(2)))
        assertEquals(setOf(token), registry.tokensFor(tag(3)))
        assertEquals(listOf(Target(token, Platform.IOS)), registry.match(tag(3)).map { it.target })
        assertEquals(1, registry.size)
    }

    @Test
    fun `an empty tag list unregisters`() = runTest {
        val registry = newRegistry()
        registry.replace(token, Platform.IOS, setOf(tag(1)), now = 1)
        registry.replace(token, Platform.IOS, emptySet(), now = 2)

        assertNull(registry.tagsOf(token))
        assertEquals(emptyList(), registry.match(tag(1)))
        assertEquals(0, registry.size)
    }

    @Test
    fun `registrations survive a restart`() = runTest {
        val path = tempDbPath()
        val first = newRegistry(path)
        first.replace(token, Platform.IOS, setOf(tag(1), tag(2)), now = 1, RegistrationOptions(true, ByteArray(32) { 7 }))
        first.replace(apnsToken(2), Platform.IOS, setOf(tag(9)), now = 1)
        first.remove(apnsToken(2))

        val second = newRegistry(path)
        second.load()
        assertEquals(1, second.size)
        assertEquals(setOf(tag(1), tag(2)), second.tagsOf(token))
        assertEquals(listOf(Target(token, Platform.IOS)), second.match(tag(2)).map { it.target })
        assertEquals(emptyList(), second.match(tag(9)))
        assertEquals(RegistrationOptions(true, ByteArray(32) { 7 }), second.optionsOf(token))
    }

    @Test
    fun `a database from before alert pushes gains the new columns`() = runTest {
        val path = tempDbPath()
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE registrations (token VARCHAR(300) PRIMARY KEY, platform VARCHAR(10) NOT NULL, tags TEXT NOT NULL, updated_at BIGINT NOT NULL)")
                it.execute("INSERT INTO registrations VALUES ('$token', 'ios', '${tag(1)}', 5)")
            }
        }
        val registry = newRegistry(path).apply { load() }
        assertEquals(RegistrationOptions(false, null), registry.optionsOf(token))
        registry.replace(token, Platform.IOS, setOf(tag(1)), 6, RegistrationOptions(true, ByteArray(32)))
        assertEquals(RegistrationOptions(true, ByteArray(32)), newRegistry(path).apply { load() }.optionsOf(token))
    }

    @Test
    fun `stale registrations expire and stay expired after a restart`() = runTest {
        val path = tempDbPath()
        val registry = newRegistry(path)
        registry.replace(apnsToken(1), Platform.IOS, setOf(tag(1)), now = 100)
        registry.replace(apnsToken(2), Platform.IOS, setOf(tag(1)), now = 200)
        // Saving the same set refreshes updated_at.
        registry.replace(apnsToken(1), Platform.IOS, setOf(tag(1)), now = 300)
        registry.replace(apnsToken(3), Platform.IOS, setOf(tag(2)), now = 50)

        assertEquals(2, registry.expire(cutoff = 250))
        assertEquals(listOf(Target(apnsToken(1), Platform.IOS)), registry.match(tag(1)).map { it.target })

        val reloaded = newRegistry(path).apply { load() }
        assertEquals(1, reloaded.size)
    }

    @Test
    fun `new tokens are refused beyond the cap, existing ones can still change`() = runTest {
        val registry = newRegistry(max = 2)
        assertEquals(ReplaceResult.OK, registry.replace(apnsToken(1), Platform.IOS, setOf(tag(1)), 1))
        assertEquals(ReplaceResult.OK, registry.replace(apnsToken(2), Platform.IOS, setOf(tag(1)), 1))
        assertEquals(ReplaceResult.FULL, registry.replace(apnsToken(3), Platform.IOS, setOf(tag(1)), 1))
        assertEquals(ReplaceResult.OK, registry.replace(apnsToken(2), Platform.IOS, setOf(tag(2)), 2))
        assertFalse(registry.contains(apnsToken(3)))
        assertTrue(registry.remove(apnsToken(1)))
        assertFalse(registry.remove(apnsToken(1)))
    }
}
