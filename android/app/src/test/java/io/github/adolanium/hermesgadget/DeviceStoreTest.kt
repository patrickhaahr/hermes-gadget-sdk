package io.github.adolanium.hermesgadget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DeviceStoreTest {
    @get:Rule val dir = TemporaryFolder()

    @Test
    fun valuesSurviveARestart() {
        val key = "c2VjcmV0LWtleS1ieXRlcy0zMi1sb25nLi4uLi4uLi4="
        DeviceStore(dir.root).apply {
            set("device_key", key)
            set("name", "Küche ☕ 🤖")
            set("volume", "70")
            erase("volume")
        }
        val reopened = DeviceStore(dir.root)
        assertEquals(key, reopened.get("device_key"))
        assertEquals("Küche ☕ 🤖", reopened.get("name"))
        assertNull(reopened.get("volume"))
    }

    @Test
    fun aFailedWriteKeepsTheOldValues() {
        val store = DeviceStore(dir.root)
        store.set("device_key", "old")
        // A directory where the temporary file goes makes the next save fail.
        File(dir.root, ".device.properties.tmp").mkdir()
        try {
            store.set("device_key", "new")
            fail("expected the save to fail")
        } catch (_: IOException) {
        }
        assertEquals("old", store.get("device_key"))
        assertEquals("old", DeviceStore(dir.root).get("device_key"))
        assertFalse(File(dir.root, "device.properties.tmp").exists())
    }
}
