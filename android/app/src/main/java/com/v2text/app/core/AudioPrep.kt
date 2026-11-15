package com.v2text.app.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Optional digital gain applied to the copy of the audio given to the recognizer.
 * The saved recording is never modified, so Retry and the benchmark see the raw take.
 *
 * Samsung's VOICE_RECOGNITION mic source delivers unprocessed, low-gain audio
 * (no AGC). This raises quiet speech toward [targetPeakDbfs] with a hard cap
 * [maxGainDb], and never amplifies clipped or already-loud input.
 */
object AudioPrep {
    data class Result(val audio: FloatArray, val gainDb: Double)

    /**
     * Trim leading/trailing silence before recognition (Whisper tends to hallucinate on long silences,
     * and it saves time), keeping [marginMs] around the speech. Uses the same adaptive threshold as
     * AudioDiagnostics. Returns the input unchanged when no speech is found.
     */
    fun trimSilence(a: FloatArray, sr: Int = 16000, marginMs: Int = 250): FloatArray {
        val n = sr / 50                         // 20 ms frames
        val frames = a.size / n
        if (frames < 5) return a
        val db = DoubleArray(frames) { f ->
            var e = 0.0
            for (i in f * n until (f + 1) * n) e += a[i] * a[i]
            20 * log10(Math.sqrt(e / n).coerceAtLeast(1e-9))
        }
        val floor = db.sorted()[(frames - 1) / 10]
        val thr = maxOf(AudioDiagnostics.MIN_SPEECH_DBFS, minOf(floor + 10, -45.0))
        val first = db.indexOfFirst { it > thr }
        val last = db.indexOfLast { it > thr }
        if (first < 0) return a
        val m = sr * marginMs / 1000
        val start = maxOf(0, first * n - m)
        val end = minOf(a.size, (last + 1) * n + m)
        return if (start == 0 && end == a.size) a else a.copyOfRange(start, end)
    }

    fun normalize(a: FloatArray, targetPeakDbfs: Double = -3.0, maxGainDb: Double = 24.0): Result {
        var peak = 0f
        for (s in a) { val v = abs(s); if (v > peak) peak = v }
        if (peak < 1e-5f || peak >= 0.5f) return Result(a, 0.0)          // silent, or loud enough already
        val peakDb = 20 * log10(peak.toDouble())
        val gainDb = (targetPeakDbfs - peakDb).coerceIn(0.0, maxGainDb)
        if (gainDb < 1.0) return Result(a, 0.0)
        val g = 10.0.pow(gainDb / 20).toFloat()
        return Result(FloatArray(a.size) { (a[it] * g).coerceIn(-1f, 1f) }, Math.round(gainDb * 10) / 10.0)
    }
}
