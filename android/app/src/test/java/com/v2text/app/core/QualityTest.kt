package com.v2text.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class QualityTest {
    // ---- hallucination filter
    @Test fun removesNonSpeechTags() =
        assertEquals("Hello there", TranscriptFilter.clean("[Music] Hello there (upbeat music) ♪♪").text)

    @Test fun removesInventedOutrosButKeepsThankYou() {
        assertEquals("Send the report.", TranscriptFilter.clean("Send the report. Thanks for watching!").text)
        assertEquals("", TranscriptFilter.clean("Please subscribe to my channel.").text)
        assertEquals("Thank you.", TranscriptFilter.clean("Thank you.").text)
    }

    @Test fun collapsesDecoderLoops() {
        assertEquals("I will go there.", TranscriptFilter.clean("I will go there. I will go there. I will go there.").text)
        assertEquals("no no", TranscriptFilter.clean("no no").text)                         // short legit repetition kept
        assertEquals("ok", TranscriptFilter.clean("ok ok ok ok ok").text)
        assertEquals("one two one two", TranscriptFilter.clean("one two one two").text)       // only 2 reps: kept
    }

    @Test fun devanagariNotDamagedByFilter() =
        assertEquals("मैं कल ऑफिस आऊंगा", TranscriptFilter.clean("मैं कल ऑफिस आऊंगा").text)

    // ---- repeated words
    @Test fun stuttersRemovedLegitDoublesKept() {
        assertEquals("Send the report", TextProcessor.clean("send the the report").text)
        assertEquals("I know that that is true", TextProcessor.clean("I know that that is true").text)
        assertEquals("Call 5 5 times", TextProcessor.clean("call 5 5 times").text)
        assertEquals("मैं कल आऊंगा", TextProcessor.clean("मैं मैं कल आऊंगा").text)
    }

    // ---- context continuation
    @Test fun continuesSentenceMidway() {
        assertEquals("and the report is ready.", TextProcessor.continueSentence("And the report is ready.", "I checked the tank"))
        assertEquals("And the report is ready.", TextProcessor.continueSentence("And the report is ready.", "Done. "))
        assertEquals("And then.", TextProcessor.continueSentence("And then.", null))
        assertEquals("And then.", TextProcessor.continueSentence("And then.", ""))
        assertEquals("I will call.", TextProcessor.continueSentence("I will call.", "ok so"))
        assertEquals("LNG is low.", TextProcessor.continueSentence("LNG is low.", "today"))
        assertEquals("Command Centre called.", TextProcessor.continueSentence("Command Centre called.", "today",
            listOf("Command Centre")))
    }

    // ---- silence trimming
    @Test fun trimsLeadingAndTrailingSilenceKeepsMargin() {
        val a = FloatArray(48000) { i -> if (i in 16000 until 32000) (0.1 * sin(2 * PI * 200 * i / 16000)).toFloat() else 0f }
        val t = AudioPrep.trimSilence(a)
        assertEquals(16000 + 2 * 4000, t.size)           // 1 s speech + 250 ms each side
        assertTrue(AudioPrep.trimSilence(FloatArray(16000)).size == 16000)   // no speech: unchanged
    }
}
