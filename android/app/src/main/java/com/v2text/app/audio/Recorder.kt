package com.v2text.app.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * 16 kHz mono PCM16 capture from the VOICE_RECOGNITION source.
 *
 * - warm (pre-roll) mode keeps the mic open while the bubble is shown and keeps the
 *   last [prerollMs] of audio in a ring buffer, prepended when recording starts
 *   (Android shows the green mic indicator the whole time).
 * - [tailMs] of audio is kept after stop so the last word isn't cut.
 * - [onLive] fires only when the first real audio buffer arrives, so the UI never
 *   says "recording" before capture is actually running.
 * - Memory is bounded at [maxSeconds].
 */
class Recorder(
    private val prerollMs: Int,
    private val tailMs: Int,
    private val warm: Boolean,
    private val maxSeconds: Int = 300,
) {
    companion object { const val SR = 16000; private const val CHUNK = 320 } // 20 ms

    data class Timing(val pressedAt: Long, val liveAt: Long, val stoppedAt: Long, val prerollSamples: Int) {
        val startLatencyMs get() = if (liveAt > 0) liveAt - pressedAt else -1
    }

    @Volatile var level = 0f; private set
    @Volatile var capturing = false; private set
    var route: String = "unknown"; private set
    var onLive: (() -> Unit)? = null

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val lock = Object()
    private val ring = ArrayDeque<ShortArray>()
    private val ringMax = maxOf(1, prerollMs / 20)
    private var buf = ShortArrayBuilder()
    private var pressedAt = 0L
    private var liveAt = 0L
    private var preroll = 0

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before starting the service
    private fun open() {
        if (record != null) return
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 2 * 8))
        check(r.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be opened (in use by another app?)" }
        record = r
        r.startRecording()
        running = true
        thread = Thread(::loop, "v2text-mic").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun loop() {
        val chunk = ShortArray(CHUNK)
        val r = record ?: return
        while (running) {
            val n = r.read(chunk, 0, CHUNK)
            if (n <= 0) continue
            var e = 0.0
            for (i in 0 until n) e += chunk[i] * chunk[i].toDouble()
            level = (sqrt(e / n) / 32768.0).toFloat()
            val piece = chunk.copyOf(n)
            synchronized(lock) {
                if (capturing) {
                    if (liveAt == 0L) { liveAt = SystemClock.elapsedRealtime(); onLive?.invoke() }
                    if (buf.size < maxSeconds * SR) buf.add(piece)
                } else if (warm) {
                    ring.addLast(piece); while (ring.size > ringMax) ring.removeFirst()
                }
            }
        }
    }

    private fun describeRoute() {
        val d: AudioDeviceInfo? = record?.routedDevice
        route = when (d?.type) {
            null -> "unknown"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth: ${d.productName}"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "wired: ${d.productName}"
            else -> "${d.productName} (type ${d.type})"
        }
    }

    /** Called when the bubble appears; opens the mic early only in warm (pre-roll) mode. */
    fun prepare() { if (warm) open() }

    fun start() {
        synchronized(lock) {
            pressedAt = SystemClock.elapsedRealtime(); liveAt = 0L
            buf = ShortArrayBuilder()
            preroll = 0
            if (warm) { ring.forEach { buf.add(it); preroll += it.size }; ring.clear() }
            capturing = true
        }
        open()
        describeRoute()
    }

    /** Stops capture after the tail and returns the audio. */
    fun stop(): Pair<ShortArray, Timing> {
        if (tailMs > 0) Thread.sleep(tailMs.toLong())
        val out: ShortArray
        synchronized(lock) { capturing = false; out = buf.build(); buf = ShortArrayBuilder() }
        val t = Timing(pressedAt, liveAt, SystemClock.elapsedRealtime(), preroll)
        if (!warm) close()
        level = 0f
        return out to t
    }

    /** Discard the current take (user cancelled or dragged the bubble). */
    fun cancel() {
        synchronized(lock) { capturing = false; buf = ShortArrayBuilder() }
        if (!warm) close()
    }

    fun close() {
        running = false
        thread?.join(500); thread = null
        record?.let { try { it.stop() } catch (_: IllegalStateException) { }; it.release() }
        record = null
    }

    private class ShortArrayBuilder {
        private val parts = ArrayList<ShortArray>()
        var size = 0; private set
        fun add(p: ShortArray) { parts += p; size += p.size }
        fun build(): ShortArray {
            val out = ShortArray(size); var o = 0
            for (p in parts) { p.copyInto(out, o); o += p.size }
            return out
        }
    }
}
