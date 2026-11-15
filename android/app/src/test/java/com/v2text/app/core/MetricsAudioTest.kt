package com.v2text.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class MetricsAudioTest {
    @Test fun werCer() {
        assertEquals(0.0, Metrics.wer("send the LNG report", "send the LNG report."), 1e-9)
        assertEquals(0.5, Metrics.wer("a b c d", "a x c"), 1e-9)
        assertEquals(0.25, Metrics.cer("abcd", "abxd"), 1e-9)
        assertEquals(2 to 2, Metrics.criticalHits(listOf("LNG", "3 pm"), "the lng at 3 PM"))
        assertTrue(Metrics.firstWordLost("hello there friend", "there friend"))
    }

    @Test fun werUnicode() {
        assertEquals(0.0, Metrics.wer("मैं कल आऊंगा।", "मैं कल आऊंगा"), 1e-9)
        assertEquals(0.0, Metrics.wer("આવતીકાલે ટેન્કર આવશે.", "આવતીકાલે ટેન્કર આવશે"), 1e-9)
    }

    @Test fun manifestParsing() {
        val items = Bench.parseManifest(
            "audio,reference,language,critical_terms\nclips/a.wav,\"Hello, LNG world\",en-IN,\"LNG;3 pm\"\n")
        assertEquals(1, items.size)
        assertEquals("Hello, LNG world", items[0].reference)
        assertEquals(listOf("LNG", "3 pm"), items[0].criticalTerms)
    }

    @Test fun diagnostics() {
        assertFalse(AudioDiagnostics.analyze(FloatArray(16000)).speechDetected)
        val loud = FloatArray(16000) { 1f }
        val r = AudioDiagnostics.analyze(loud)
        assertTrue(r.clippingPct > 50)
        assertTrue(r.warnings.any { "clipping" in it })
        assertTrue(r.warnings.any { "first word" in it })
        val tone = FloatArray(16000) { i -> if (i in 3200..12800) (0.2 * sin(2 * PI * 220 * i / 16000)).toFloat() else 0f }
        val t = AudioDiagnostics.analyze(tone)
        assertTrue(t.speechDetected)
        assertTrue(t.warnings.isEmpty())
        assertEquals(200, t.onsetMs)
    }

    @Test fun wavRoundTripAndResample() {
        val a = FloatArray(48000) { (0.3 * sin(it / 20.0)).toFloat() }
        val bytes = Wav.encode(Wav.toPcm(a), 48000)
        val b = Wav.decode(bytes, 16000)
        assertTrue(abs(b.size - 16000) <= 1)
        val same = Wav.decode(Wav.encode(Wav.toPcm(a.copyOf(1600)), 16000), 16000)
        assertEquals(1600, same.size)
        assertEquals(a[100], same[100], 1e-3f)
    }
}
