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
