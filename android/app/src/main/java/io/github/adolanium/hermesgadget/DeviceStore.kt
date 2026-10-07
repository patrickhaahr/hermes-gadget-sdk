package io.github.adolanium.hermesgadget

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

/**
 * The core's key/value storage: the device key, the connection settings and the
 * on-device preferences. Each change is written to a temporary file, synced and
 * renamed over the old one, so a crash or power loss keeps either version whole.
 * Keep the directory out of backups: the device key must not be cloned to another phone.
 */
class DeviceStore(directory: File) {
    private val file = File(directory, "device.properties")
    private val values = Properties()

    init {
        directory.mkdirs()
        if (file.exists()) file.inputStream().use { values.load(it) }
    }

    @Synchronized
    fun get(key: String): String? = values.getProperty(key)

    @Synchronized
    @Throws(IOException::class)
    fun set(key: String, value: String) {
        val previous = values.getProperty(key)
        values.setProperty(key, value)
        try {
            save()
        } catch (e: IOException) {
            if (previous == null) values.remove(key) else values.setProperty(key, previous)
            throw e
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun erase(key: String) {
        val previous = values.remove(key) ?: return
        try {
            save()
        } catch (e: IOException) {
            values[key] = previous
            throw e
        }
    }

    private fun save() {
        val temp = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temp).use { out ->
            values.store(out, null)
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("could not replace $file")
        }
    }
}
