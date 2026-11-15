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

    @Test fun pausesDoNotMakeNormalSpeechLookQuiet() {
        // 0.5 s of speech at about -30 dBFS speech level inside 4 s of near-silence
        val a = FloatArray(64000) { i -> if (i in 28000 until 36000) (0.045 * sin(2 * PI * 200 * i / 16000)).toFloat() else 0.0005f }
        val r = AudioDiagnostics.analyze(a)
        assertTrue(r.speechDetected)
        assertTrue("whole-clip RMS is low", r.rmsDbfs < -38)
        assertTrue("speech RMS is normal: ${r.speechRmsDbfs}", r.speechRmsDbfs > -35)
        assertFalse(r.warnings.any { "quiet" in it })
    }

    @Test fun reallyQuietSpeechIsFlagged() {
        // about -46 dBFS speech between pauses: still detected as speech, and flagged as quiet
        val a = FloatArray(48000) { i -> if (i in 16000 until 32000) (0.007 * sin(2 * PI * 200 * i / 16000)).toFloat() else 0f }
        val r = AudioDiagnostics.analyze(a)
        assertTrue("quiet speech must not be dropped as silence", r.speechDetected)
        assertTrue(r.warnings.any { "quiet" in it })
    }

    @Test fun gainRaisesQuietAudioButNeverLoudOrSilent() {
        val quiet = FloatArray(16000) { i -> (0.02 * sin(2 * PI * 200 * i / 16000)).toFloat() }
        val r = AudioPrep.normalize(quiet)
        assertTrue(r.gainDb in 20.0..24.0)
        assertTrue(r.audio.maxOf { abs(it) } <= 1f)
        assertEquals(0.02f, quiet.maxOf { abs(it) }, 1e-3f)          // input untouched
        assertEquals(0.0, AudioPrep.normalize(FloatArray(100) { 0.8f }).gainDb, 0.0)
        assertEquals(0.0, AudioPrep.normalize(FloatArray(100)).gainDb, 0.0)
        val faint = FloatArray(100) { 0.001f }                          // capped at +24 dB
        assertEquals(24.0, AudioPrep.normalize(faint).gainDb, 0.01)
    }
}
