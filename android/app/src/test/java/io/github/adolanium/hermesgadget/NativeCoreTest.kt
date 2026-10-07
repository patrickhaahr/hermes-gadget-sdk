package io.github.adolanium.hermesgadget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * The production core behind the JNI bridge (hgjni, built for this machine),
 * driven through the protocol by a fake host: if a callback signature or the
 * UTF-8 handling in hgjni.cpp is wrong, these fail.
 */
class NativeCoreTest {
    private class FakeHost : NativeHost {
        val storage = mutableMapOf("server" to "ws://zaza:8765/gadget", "name" to "Küche 🤖")
        val sentText = mutableListOf<String>()
        val played = mutableListOf<Short>()
        var connectedTo: Pair<String, String>? = null
        var flushedRows = 0
        var speakerRate = 0
        var clock = 0L

        override fun transportConnect(url: ByteArray, subprotocol: ByteArray) {
            connectedTo = url.decodeToString() to subprotocol.decodeToString()
        }
        override fun transportSendText(data: ByteArray): Boolean = sentText.add(data.decodeToString())
        override fun transportSendBinary(data: ByteArray) = true
        override fun transportClose() {}
        override fun displayFlush(y0: Int, y1: Int) {
            flushedRows += y1 - y0
        }
        override fun displayBacklight(percent: Int) {}
        override fun micStart(rate: Int) = true
        override fun micStop() {}
        override fun speakerBegin(rate: Int): Boolean {
            speakerRate = rate
            return true
        }
        override fun speakerWrite(samples: ShortArray) {
            played += samples.toList()
        }
        override fun speakerEnd() {}
        override fun speakerAbort() {}
        override fun speakerBusy() = false
        override fun speakerVolume(percent: Int) {}
        override fun storageGet(key: ByteArray) = storage[key.decodeToString()]?.toByteArray()
        override fun storageSet(key: ByteArray, value: ByteArray) {
            storage[key.decodeToString()] = value.decodeToString()
        }
        override fun storageErase(key: ByteArray) {
            storage.remove(key.decodeToString())
        }
        override fun nowMs() = clock
        override fun randomBytes(count: Int) = ByteArray(count) { (it * 7 + 1).toByte() }
        var logs = 0
        var throwFromLog = false
        override fun log(level: Int, message: ByteArray) {
            logs++
            if (throwFromLog) throw IllegalStateException("from log")
        }
    }

    private val host = FakeHost()
    private var handle = 0L

    @Before
    fun create() {
        assertEquals(NativeCore.ABI_VERSION, NativeCore.abiVersion())
        handle = NativeCore.create(host, 360, 800, hasMic = true, hasSpeaker = true, micRate = 16000,
            speakerRate = 24000, board = "android".toByteArray(), firmware = "0.2.0".toByteArray(),
            defaultName = "Android Gadget".toByteArray(), talkLabel = null, cancelLabel = null, touch = true)
        assertTrue(handle != 0L)
        NativeCore.begin(handle)
    }

    @After
    fun destroy() = NativeCore.destroy(handle)

    private fun run(ms: Int) {
        repeat(ms / 10) {
            host.clock += 10
            NativeCore.tick(handle)
        }
    }

    private fun lastSent(type: String) = host.sentText.lastOrNull { "\"type\":\"$type\"" in it }

    private fun online() {
        NativeCore.network(handle, true, "Wi-Fi".toByteArray())
        run(2000)
        assertEquals("ws://zaza:8765/gadget" to "hermes-gadget.v1", host.connectedTo)
        NativeCore.transportOpen(handle)
        run(50)
        val nonce = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        NativeCore.transportText(handle, """{"type":"challenge","nonce":"$nonce","enrolled":false}""".toByteArray())
        run(50)
        NativeCore.transportText(handle,
            """{"type":"welcome","session":"s1","paired":true,"heartbeat_s":20,"server":"hermes","proto":1}""".toByteArray())
        run(50)
    }

    @Test
    fun handshakeIdentifiesAnAndroidDevice() {
        online()
        val hello = checkNotNull(lastSent("hello")) { "no hello sent" }
        assertTrue(hello, "\"board\":\"android\"" in hello)
        assertTrue(hello, "\"name\":\"Küche 🤖\"" in hello)
        assertTrue(hello, "\"speaker\":{\"rate\":24000" in hello)
        // First contact: the key travels once, and its hash is the device id.
        val auth = checkNotNull(lastSent("auth")) { "no auth sent" }
        assertTrue(auth, "\"key\":" in auth)
        assertTrue(host.storage.containsKey("device_key"))
        val status = NativeCore.status(handle).decodeToString()
        assertTrue(status, "\"phase\":\"online\"" in status)
        assertTrue(status, "\"paired\":true" in status)
    }

    @Test
    fun aThrowingCallbackDoesNotStopTheCore() {
        host.throwFromLog = true
        online()
        assertTrue(host.logs > 0)
        assertTrue("\"phase\":\"online\"" in NativeCore.status(handle).decodeToString())
    }

    @Test
    fun repliesOutsideTheBasicPlaneReachTheCore() {
        online()
        NativeCore.transportText(handle, """{"type":"turn.start","turn":"t1"}""".toByteArray())
        NativeCore.transportText(handle, """{"type":"reply","turn":"t1","text":"Done 🤖✅"}""".toByteArray())
        run(100)
        assertEquals("responding", NativeCore.screen(handle).decodeToString())
        assertTrue(host.flushedRows > 0)
        assertNotNull(NativeCore.framebuffer(handle))
    }

    @Test
    fun speechFromTheServerIsPlayed() {
        online()
        NativeCore.transportText(handle, """{"type":"audio.start","stream":7,"rate":24000,"format":"pcm16"}""".toByteArray())
        val frame = byteArrayOf(0x01, 7, 0, 0, 0x34, 0x12, 0xCC.toByte(), 0xED.toByte())
        NativeCore.transportBinary(handle, frame)
        run(100)
        assertEquals(24000, host.speakerRate)
        assertEquals(listOf<Short>(0x1234, -0x1234), host.played)
    }

    @Test
    fun theConsoleAnswersThroughTheBridge() {
        val reply = NativeCore.console(handle, "set talk_mode tap".toByteArray()).decodeToString()
        assertTrue(reply, reply.startsWith("@ok"))
        assertEquals("tap", host.storage["talk_mode"])
    }
}
