package io.github.adolanium.hermesgadget

/** What a wake activates. Live voice is reserved until its call controller is available. */
enum class VoiceMode(val value: String) {
    HERMES("hermes"), LIVE("live");
}
