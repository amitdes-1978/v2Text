package com.v2text.app.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    fun resample(a: FloatArray, src: Int, dst: Int): FloatArray {
        if (src == dst || a.isEmpty()) return a
        var x = a
        if (src > dst) { // moving-average low-pass before decimation
            val k = Math.ceil(src.toDouble() / dst).toInt()
            x = FloatArray(a.size) { i ->
                var s = 0f; var n = 0
                for (j in i - k / 2..i + (k - 1) / 2) if (j in a.indices) { s += a[j]; n++ }
                s / n
            }
        }
        val n = Math.round(x.size.toDouble() * dst / src).toInt()
        return FloatArray(n) { i ->
            val p = if (n == 1) 0.0 else i * (x.size - 1).toDouble() / (n - 1)
            val i0 = p.toInt(); val i1 = minOf(i0 + 1, x.size - 1); val fr = (p - i0).toFloat()
            x[i0] * (1 - fr) + x[i1] * fr
        }
    }

    fun toFloat(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }

    fun toPcm(a: FloatArray) = ShortArray(a.size) { (a[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }

    fun pcmToBytes(pcm: ShortArray): ByteArray {
        val bo = ByteArrayOutputStream(pcm.size * 2)
        for (s in pcm) { bo.write(s.toInt() and 0xff); bo.write((s.toInt() shr 8) and 0xff) }
        return bo.toByteArray()
    }
}
