package io.github.adolanium.hermesgadget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GadgetConfigTest {
    @get:Rule val dir = TemporaryFolder()

    @Test
    fun acceptsGadgetUrls() {
        assertNull(GadgetConfig("ws://192.168.1.20:8765/gadget", "Robot").problem())
        assertNull(GadgetConfig("wss://zaza.tail1234.ts.net/gadget", "Robot", "secret").problem())
    }

    @Test
    fun rejectsWhatTheLinuxClientRejects() {
        for (url in listOf("http://zaza:8765/gadget", "ws:///gadget", "ws://user:pw@zaza/gadget", "ws://zaza/gadget#x", "zaza:8765")) {
            assertNotNull(url, GadgetConfig(url, "Robot").problem())
        }
        assertNotNull(GadgetConfig("ws://zaza/gadget", " ").problem())
        assertNotNull(GadgetConfig("ws://zaza/gadget", "x".repeat(501)).problem())
        assertNotNull(GadgetConfig("ws://zaza/gadget", "Robot", "a\u0000b").problem())
    }

    @Test
    fun storesUnderTheKeysTheCoreReads() {
        val store = DeviceStore(dir.root)
        GadgetConfig("ws://zaza:8765/gadget", "Robot", "secret").writeTo(store)
        assertEquals("ws://zaza:8765/gadget", store.get("server"))
        assertEquals("Robot", store.get("name"))
        assertEquals("secret", store.get("token"))

        GadgetConfig("ws://zaza:8765/gadget", "Robot").writeTo(store)
        assertNull("an empty token is erased, not stored", store.get("token"))
        assertEquals(GadgetConfig("ws://zaza:8765/gadget", "Robot"), GadgetConfig.readFrom(DeviceStore(dir.root)))
    }

    @Test
    fun unconfiguredWithoutAServer() {
        assertNull(GadgetConfig.readFrom(DeviceStore(dir.root)))
    }
}
