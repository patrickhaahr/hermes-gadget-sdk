package io.github.adolanium.hermesgadget

import java.net.URI
import java.net.URISyntaxException

/**
 * Connection settings, checked the same way as the Linux client's config.json.
 * They live in the core's storage under the keys the core reads at boot.
 */
data class GadgetConfig(val server: String, val name: String, val token: String = "") {
    /** Why this configuration can't be used, or null when it can. */
    fun problem(): String? {
        for ((field, value) in listOf("server" to server, "name" to name, "token" to token)) {
            if (value.toByteArray().size > 500 || '\u0000' in value) return "$field is too long or has a NUL character"
        }
        if (name.isBlank()) return "name is required"
        val url = try {
            URI(server)
        } catch (_: URISyntaxException) {
            return "server is not a valid URL"
        }
        if (url.scheme !in setOf("ws", "wss") || url.host.isNullOrEmpty() || url.rawUserInfo != null ||
            url.rawFragment != null
        ) {
            return "server must be a ws:// or wss:// URL without credentials or a fragment"
        }
        return null
    }

    fun writeTo(store: DeviceStore) {
        store.set(KEY_SERVER, server)
        store.set(KEY_NAME, name)
        if (token.isEmpty()) store.erase(KEY_TOKEN) else store.set(KEY_TOKEN, token)
    }

    companion object {
        const val KEY_SERVER = "server"
        const val KEY_NAME = "name"
        const val KEY_TOKEN = "token"
        const val DEFAULT_NAME = "Android Gadget"

        fun readFrom(store: DeviceStore): GadgetConfig? {
            val server = store.get(KEY_SERVER) ?: return null
            return GadgetConfig(server, store.get(KEY_NAME) ?: DEFAULT_NAME, store.get(KEY_TOKEN) ?: "")
        }
    }
}
