package com.v2text.app.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Signal-quality report for one recording (blueprint section 7.2). */
data class AudioReport(
    val durationS: Double,
    val rmsDbfs: Double,
    val peakDbfs: Double,
    val clippingPct: Double,
    val silenceRatio: Double,
    val onsetMs: Int?,
    val trailingSilenceMs: Int?,
    val speechDetected: Boolean,
    val warnings: List<String>,
) {
    fun toJson(): String = buildString {
        append("{\"duration_s\":$durationS,\"rms_dbfs\":$rmsDbfs,\"peak_dbfs\":$peakDbfs,")
        append("\"clipping_pct\":$clippingPct,\"silence_ratio\":$silenceRatio,")
        append("\"onset_ms\":$onsetMs,\"trailing_silence_ms\":$trailingSilenceMs,\"speech\":$speechDetected,")
        append("\"warnings\":[" + warnings.joinToString(",") { "\"" + it.replace("\"", "'") + "\"" } + "]}")
    }
}

object AudioDiagnostics {
    const val FRAME_MS = 20

    private fun db(x: Double) = 20 * log10(max(x, 1e-9))
    private fun r(x: Double, d: Int = 1): Double { val p = Math.pow(10.0, d.toDouble()); return Math.round(x * p) / p }

    fun analyze(a: FloatArray, sr: Int = 16000, silenceDbfs: Double = -45.0): AudioReport {
        if (a.isEmpty()) return AudioReport(0.0, -180.0, -180.0, 0.0, 1.0, null, null, false, listOf("empty recording"))
        var sum = 0.0; var peak = 0.0; var clipped = 0
        for (s in a) { sum += s * s; val v = abs(s.toDouble()); if (v > peak) peak = v; if (v >= 0.99) clipped++ }
        val rms = sqrt(sum / a.size)
        val clip = clipped * 100.0 / a.size
        val n = max(1, sr * FRAME_MS / 1000)
        val frames = max(1, a.size / n)
        val voiced = BooleanArray(frames) { f ->
            var e = 0.0
            val end = minOf(a.size, (f + 1) * n)
            for (i in f * n until end) e += a[i] * a[i]
            db(sqrt(e / max(1, end - f * n))) > silenceDbfs
        }
        val nVoiced = voiced.count { it }
        val speech = nVoiced >= 3                // >= 60 ms of signal above the floor
        val onset = if (speech) voiced.indexOfFirst { it } * FRAME_MS else null
        val trailing = if (speech) (frames - 1 - voiced.indexOfLast { it }) * FRAME_MS else null
        val w = mutableListOf<String>()
        if (!speech) w += "no speech detected (check microphone / permission / Bluetooth route)"
        else {
            if (db(rms) < -38) w += "input is very quiet: move closer to the microphone"
            if (clip > 0.1) w += "clipping on ${"%.2f".format(clip)}% of samples: speak a little further away"
            if (onset != null && onset < FRAME_MS * 2) w += "speech starts at the very first frame: first word may be clipped (enable pre-roll)"
            if (trailing != null && trailing < FRAME_MS * 2) w += "speech runs to the last frame: last word may be cut (raise tail)"
        }
        return AudioReport(r(a.size.toDouble() / sr, 3), r(db(rms)), r(db(peak)), r(clip, 3),
            r(1 - nVoiced.toDouble() / frames, 3), onset, trailing, speech, w)
    }
}
