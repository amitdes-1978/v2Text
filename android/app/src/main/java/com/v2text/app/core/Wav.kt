package com.v2text.app.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** 16-bit PCM WAV encode/decode + resampling. Pure JVM, unit-tested. */
object Wav {
    fun encode(pcm: ShortArray, sampleRate: Int = 16000): ByteArray {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(sampleRate); b.putInt(sampleRate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        for (s in pcm) b.putShort(s)
        return b.array()
    }

    /** Decode a PCM16 WAV to mono float at [targetRate]. Walks chunks, so LIST/fact chunks are fine. */
    fun decode(bytes: ByteArray, targetRate: Int = 16000): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var pos = 12
        var channels = 1; var rate = 16000; var bits = 16; var fmt = 1
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = b.getInt(pos + 4)
            if (id == "fmt ") {
                fmt = b.getShort(pos + 8).toInt(); channels = b.getShort(pos + 10).toInt()
                rate = b.getInt(pos + 12); bits = b.getShort(pos + 22).toInt()
            } else if (id == "data") {
                dataOff = pos + 8; dataLen = minOf(len, bytes.size - dataOff); break
            }
            pos += 8 + len + (len and 1)
        }
        require(dataOff >= 0) { "WAV has no data chunk" }
        require(fmt == 1 && bits == 16) { "only 16-bit PCM WAV is supported (got format=$fmt bits=$bits)" }
        val frames = dataLen / (2 * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) acc += b.getShort(dataOff + (f * channels + c) * 2) / 32768f
            out[f] = acc / channels
        }
        return resample(out, rate, targetRate)
    }

    /**
     * Band-limited resampling. The old moving-average filter followed by linear interpolation
     * was not a proper anti-aliasing filter (especially for 44.1/48 kHz -> 16 kHz speech).
     * A windowed-sinc low-pass is used for both downsampling and interpolation.
     */
    fun resample(a: FloatArray, src: Int, dst: Int): FloatArray {
        require(src > 0 && dst > 0) { "sample rates must be positive" }
        if (src == dst || a.isEmpty()) return a

        val outputSize = (a.size.toDouble() * dst / src).roundToInt().coerceAtLeast(1)
        val ratio = dst.toDouble() / src
        val cutoff = 0.5 * minOf(1.0, ratio) * 0.94 // guard band below the destination Nyquist frequency
        val radius = 16
        val out = FloatArray(outputSize)

        for (i in out.indices) {
            val position = i / ratio
            val center = floor(position).toInt()
            var weighted = 0.0
            var weightSum = 0.0
            for (j in (center - radius + 1)..(center + radius)) {
                if (j !in a.indices) continue
                val distance = position - j
                val scaled = distance * cutoff
                if (abs(distance) >= radius) continue
                val sinc = if (abs(scaled) < 1e-9) 1.0 else sin(PI * scaled) / (PI * scaled)
                // Hann window, zero at the finite filter boundary.
                val window = 0.5 + 0.5 * cos(PI * distance / radius)
                val weight = cutoff * sinc * window
                weighted += a[j] * weight
                weightSum += weight
            }
            out[i] = if (abs(weightSum) > 1e-12) (weighted / weightSum).toFloat() else 0f
        }
        return out
    }

    fun toFloat(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }

    fun toPcm(a: FloatArray) = ShortArray(a.size) { (a[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }

    fun pcmToBytes(pcm: ShortArray): ByteArray {
        val bo = ByteArrayOutputStream(pcm.size * 2)
        for (s in pcm) { bo.write(s.toInt() and 0xff); bo.write((s.toInt() shr 8) and 0xff) }
        return bo.toByteArray()
    }
}
