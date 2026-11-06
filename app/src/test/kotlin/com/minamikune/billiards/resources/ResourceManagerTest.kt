package com.minamikune.billiards.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResourceManagerTest {
    private class TrackedClose(private val name: String, private val order: MutableList<String>) : AutoCloseable {
        var closes = 0
            private set

        override fun close() {
            closes++
            order.add(name)
        }
    }

    @Test
    fun loadsOncePerKeyAndReturnsSameInstance() {
        val manager = ResourceManager()
        var loads = 0
        val loader = {
            loads++
            StringBuilder("value")
        }
        val first = manager.get("key", loader)
        val second = manager.get("key", loader)
        assertSame(first, second)
        assertEquals(1, loads)
        assertEquals(1, manager.size())
        assertTrue(manager.isLoaded("key"))
        manager.close()
    }

    @Test
    fun failedLoaderLeavesNoPartialEntry() {
        val manager = ResourceManager()
        try {
            manager.get<String>("broken") { error("boom") }
            fail("loader exception should escape")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertFalse(manager.isLoaded("broken"))
        assertEquals(0, manager.size())
        manager.close()
    }

    @Test
    fun releaseClosesEntryAndAllowsReload() {
        val manager = ResourceManager()
        val order = mutableListOf<String>()
        val first = manager.get("audio") { TrackedClose("audio-1", order) }
        assertTrue(manager.release("audio"))
        assertEquals(1, first.closes)
        assertFalse(manager.isLoaded("audio"))
        assertFalse(manager.release("audio"))

        val second = manager.get("audio") { TrackedClose("audio-2", order) }
        assertTrue(first !== second)
        manager.close()
        assertEquals(listOf("audio-1", "audio-2"), order)
    }

    @Test
    fun closeReleasesInReverseCreationOrderAndIsIdempotent() {
        val manager = ResourceManager()
        val order = mutableListOf<String>()
        manager.get("a") { TrackedClose("a", order) }
        manager.get("b") { TrackedClose("b", order) }
        manager.get("c") { TrackedClose("c", order) }

        manager.close()
        manager.close()

        assertEquals(listOf("c", "b", "a"), order)
        assertTrue(manager.isClosed)
        assertEquals(0, manager.size())
    }

    @Test
    fun useAfterCloseFailsFast() {
        val manager = ResourceManager()
        manager.close()
        try {
            manager.get("late") { "value" }
            fail("closed manager accepted a load")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}
