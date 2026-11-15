package com.v2text.app

import android.content.ClipboardManager
import android.content.Context
import com.v2text.app.core.DictEntry
import com.v2text.app.core.LanguageProfile
import com.v2text.app.engine.SpeechEngine
import com.v2text.app.engine.SttException
import com.v2text.app.engine.Transcript
import com.v2text.app.service.InsertResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Runs the real pipeline (SQLite, SharedPreferences, clipboard, files) with a fake speech engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationControllerTest {
    private class FakeEngine(var text: String = "um the l n g tanker GJ 01 AB 1234 arrives at 4 pm, actually 5 pm",
                             var fail: SttException? = null) : SpeechEngine {
        override val id = "fake"; override val label = "fake"
        var calls = 0; var lastProfile: LanguageProfile? = null; var lastHints: List<String> = emptyList()
        override suspend fun transcribe(audio: FloatArray, profile: LanguageProfile, hints: List<String>): Transcript {
            calls++; lastProfile = profile; lastHints = hints
            fail?.let { throw it }
            return Transcript(text, id, "fake-1", "en", 0.01)
        }
    }

    private lateinit var app: V2TextApp
    private lateinit var engine: FakeEngine

    private fun speech(seconds: Double = 1.5) = ShortArray((16000 * seconds).toInt()) { i ->
        val t = i / 16000.0
        if (t in 0.2..(seconds - 0.2)) (6000 * sin(2 * PI * 220 * t)).toInt().toShort() else 0
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as V2TextApp
        engine = FakeEngine()
        app.engineOverride = engine
        app.db.clear()
        app.controller.deleteAllAudio()
        app.settings.retention = "on_failure"
        app.settings.insertMode = "auto"
    }

    @Test fun okPathKeepsVerbatimSeparateAndFallsBackToClipboard() = runTest {
        val out = app.controller.process(speech(), null, "built-in mic")
        // No accessibility service in tests -> text can't be typed, so it must be on the clipboard.
        assertEquals("insert_failed", out.status)
        assertEquals(InsertResult.SERVICE_OFF, out.insert)
        assertEquals("The LNG tanker GJ 01 AB 1234 arrives at 5 pm", out.clean)
        val row = app.db.get(out.id)!!
        assertEquals("um the l n g tanker GJ 01 AB 1234 arrives at 4 pm, actually 5 pm", row.verbatim)
        assertEquals(out.clean, row.clean)
        assertTrue(row.changes!!.contains("self_correction"))
        assertNull("successful recognition + on_failure policy deletes audio", row.audioPath)
        val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
        assertEquals(out.clean, clip.getItemAt(0).text.toString())
        assertEquals("en-IN", engine.lastProfile!!.code)
        assertTrue("LNG" in engine.lastHints)
    }

    @Test fun silenceIsNoSpeechNotAnErrorAndAudioIsKept() = runTest {
        val out = app.controller.process(ShortArray(16000), null, null)
        assertEquals("no_speech", out.status)
        assertEquals(0, engine.calls)
        assertTrue(out.canRetry)
        assertTrue(File(app.db.get(out.id)!!.audioPath!!).exists())
    }

    @Test fun tooShort() = runTest {
        assertEquals("too_short", app.controller.process(speech(0.2), null, null).status)
        assertEquals(0, engine.calls)
    }

    @Test fun recognitionFailureKeepsAudioAndRetryCreatesNewSession() = runTest {
        engine.fail = SttException("model missing", SttException.Kind.MODEL_MISSING)
        val bad = app.controller.process(speech(), null, null)
        assertEquals("stt_error", bad.status)
        val audio = app.db.get(bad.id)!!.audioPath!!
        assertTrue(File(audio).exists())

        engine.fail = null
        val good = app.controller.retry(bad.id)
        assertTrue(good.id != bad.id)
        assertEquals(bad.id, app.db.get(good.id)!!.retryOf)
        assertEquals("stt_error", app.db.get(bad.id)!!.status)        // original record untouched
        assertNull(app.db.get(bad.id)!!.verbatim)
        assertNotNull(app.db.get(good.id)!!.verbatim)
    }

    @Test fun noMatchFromEngineIsNoSpeech() = runTest {
        engine.fail = SttException("nothing", SttException.Kind.NO_MATCH)
        assertEquals("no_speech", app.controller.process(speech(), null, null).status)
    }

    @Test fun retentionNeverStoresNoAudio() = runTest {
        app.settings.retention = "never"
        engine.fail = SttException("boom", SttException.Kind.FAILED)
        val out = app.controller.process(speech(), null, null)
        assertNull(app.db.get(out.id)!!.audioPath)
        assertFalse(out.canRetry)
        assertEquals(0, File(app.filesDir, "audio").listFiles()!!.size)
    }

    @Test fun previewModeDoesNotInsertAndDuplicateGuardWorks() = runTest {
        app.settings.insertMode = "preview"
        val out = app.controller.process(speech(), null, null)
        assertEquals("preview", out.status)
        assertFalse(app.db.get(out.id)!!.inserted)
        app.db.update(out.id, inserted = true)
        assertEquals(InsertResult.ALREADY_INSERTED, app.controller.insertSession(out.id))
    }

    @Test fun languageProfileAndDictionaryFlowThrough() = runTest {
        app.settings.language = "hi"
        app.settings.dictionary = listOf(DictEntry("Kandla Port", listOf("kandla pot")))
        engine.text = "अं kandla pot पर टैंकर 3 बजे"
        val out = app.controller.process(speech(), null, null)
        assertEquals("hi", engine.lastProfile!!.code)
        assertEquals("Kandla Port पर टैंकर 3 बजे", out.clean)
        app.settings.language = "en-IN"
        app.settings.dictionary = com.v2text.app.data.Settings.STARTER_DICTIONARY
    }

    @Test fun settingsRoundTrip() {
        app.settings.snippets = mapOf("my signature" to "Regards,\nYour Name")
        assertEquals("Regards,\nYour Name", app.settings.snippets["my signature"])
        assertEquals("Command Centre", app.settings.cleanOptions().aliases["command center"])
        app.settings.snippets = emptyMap()
    }

    @Test fun engineReadinessIsCheckedBeforeRecording() {
        app.engineOverride = null
        app.settings.engine = "whisper"
        app.settings.model = null
        val problem = app.engineProblem()
        assertNotNull(problem)
        app.engineOverride = engine
        assertNull(app.engineProblem())
    }

    @Test fun quietTakeIsBoostedForRecognitionButSavedRaw() = runTest {
        app.settings.retention = "always"
        val quiet = speech().map { (it / 12).toShort() }.toShortArray()
        var heardPeak = 0f
        app.engineOverride = object : SpeechEngine {
            override val id = "peek"; override val label = "peek"
            override suspend fun transcribe(audio: FloatArray, profile: LanguageProfile, hints: List<String>): Transcript {
                heardPeak = audio.maxOf { kotlin.math.abs(it) }; return Transcript("hello", id, "m", null, 0.0)
            }
        }
        val out = app.controller.process(quiet, null, null)
        val row = app.db.get(out.id)!!
        // raw peak ~0.015 (-36 dBFS); gain is capped at +24 dB -> ~0.24
        assertTrue("recognizer got a boosted copy: $heardPeak", heardPeak > 0.2f)
        assertTrue(row.diagnostics!!.contains("\"gain_db\":"))
        val saved = com.v2text.app.core.Wav.decode(File(row.audioPath!!).readBytes())
        assertTrue("saved audio is raw", saved.maxOf { kotlin.math.abs(it) } < 0.05f)
    }
}
