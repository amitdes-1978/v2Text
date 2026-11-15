package com.v2text.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import com.v2text.app.audio.Recorder
import com.v2text.app.core.AudioDiagnostics
import com.v2text.app.core.AudioPrep
import com.v2text.app.core.AudioReport
import com.v2text.app.core.Languages
import com.v2text.app.core.TextProcessor
import com.v2text.app.core.Wav
import com.v2text.app.engine.SttException
import com.v2text.app.service.InsertResult
import com.v2text.app.service.V2TextAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * capture -> diagnostics -> recognition -> (immutable verbatim) -> formatter -> insert.
 *
 * Each stage reports its own outcome: "no speech", "recognition failed" and
 * "insert failed" are different statuses, and recognition can be retried from
 * saved audio independently of re-inserting text.
 */
class DictationController(private val app: V2TextApp) {
    data class Outcome(
        val id: Long, val status: String, val verbatim: String = "", val clean: String = "",
        val error: String? = null, val report: AudioReport? = null, val insert: InsertResult? = null,
        val canRetry: Boolean = false,
    )

    private val audioDir = File(app.filesDir, "audio").apply { mkdirs() }

    suspend fun process(pcm: ShortArray, timing: Recorder.Timing?, route: String?, retryOf: Long? = null,
                        existingAudio: File? = null, insert: Boolean = true): Outcome {
        val s = app.settings
        val pkg = V2TextAccessibilityService.instance?.focusedPackage()
        if (pkg != null && pkg in s.blockedPackages) {
            return Outcome(record(ContentValues().apply { put("status", "blocked"); put("pkg", pkg) }), "blocked",
                error = "Dictation is disabled in this app (blocked list).")
        }
        val audio = Wav.toFloat(pcm)
        val report = AudioDiagnostics.analyze(audio, Recorder.SR)
        val base = ContentValues().apply {
            put("pkg", pkg); put("language", s.language); put("audio_seconds", report.durationS)
            put("diagnostics", report.toJson()); put("route", route); put("start_latency_ms", timing?.startLatencyMs ?: -1)
            retryOf?.let { put("retry_of", it) }
        }
        if (report.durationS * 1000 < s.minMs) {
            return Outcome(record(base.apply { put("status", "too_short") }), "too_short", report = report,
                error = "Too short. Hold the bubble while you speak, or tap once to start and again to stop.")
        }
        // Save the take before recognition so a crash or failure never loses it.
        val wav = existingAudio ?: saveWav(pcm)
        if (!report.speechDetected) {
            val kept = finalizeAudio(wav, success = false)
            return Outcome(record(base.apply { put("status", "no_speech"); put("audio_path", kept?.absolutePath) }),
                "no_speech", report = report, canRetry = kept != null, error = "No speech detected.")
        }
        val engine = app.engine()
        // Optional gain on the recognizer's copy only; the saved WAV stays raw.
        val prep = if (s.boostQuiet) AudioPrep.normalize(audio) else AudioPrep.Result(audio, 0.0)
        base.put("diagnostics", report.toJson().dropLast(1) + ",\"gain_db\":${prep.gainDb}}")
        val t = try {
            engine.transcribe(prep.audio, Languages.get(s.language), s.dictionary.map { it.term })
        } catch (e: SttException) {
            val kept = finalizeAudio(wav, success = false)
            val status = if (e.kind == SttException.Kind.NO_MATCH) "no_speech" else "stt_error"
            return Outcome(record(base.apply {
                put("status", status); put("error", e.message); put("engine", engine.id); put("audio_path", kept?.absolutePath)
            }), status, report = report, error = e.message, canRetry = kept != null)
        }
        if (t.text.isBlank()) {
            val kept = finalizeAudio(wav, success = false)
            return Outcome(record(base.apply {
                put("status", "no_speech"); put("engine", t.engine); put("model", t.model); put("audio_path", kept?.absolutePath)
            }), "no_speech", report = report, canRetry = kept != null, error = "The engine heard no words.")
        }
        val cleaned = TextProcessor.clean(t.text, s.cleanOptions())
        val kept = finalizeAudio(wav, success = true)
        val autoInsert = insert && s.insertMode == "auto"
        val id = record(base.apply {
            put("status", if (autoInsert) "ok" else "preview"); put("engine", t.engine); put("model", t.model)
            put("verbatim", t.text); put("clean", cleaned.text); put("changes", cleaned.changes.joinToString(","))
            put("stt_seconds", t.seconds); put("words", TextProcessor.wordCount(cleaned.text)); put("audio_path", kept?.absolutePath)
        })
        if (!autoInsert) return Outcome(id, "preview", t.text, cleaned.text, report = report)
        val (ins, method) = insertText(cleaned.text)
        if (id >= 0) {
            if (ins == InsertResult.OK) app.db.update(id, inserted = true, insertMethod = method)
            else app.db.update(id, status = "insert_failed", error = ins.message)
        }
        return Outcome(id, if (ins == InsertResult.OK) "ok" else "insert_failed", t.text, cleaned.text,
            error = if (ins == InsertResult.OK) null else ins.message, report = report, insert = ins)
    }

    /** [process] / [retry] that can never crash the app: any failure becomes a recorded, readable error. */
    suspend fun processSafely(pcm: ShortArray, timing: Recorder.Timing?, route: String?): Outcome =
        guard("dictation") { process(pcm, timing, route) }

    suspend fun retrySafely(id: Long): Outcome = guard("retry #$id") { retry(id) }

    private suspend fun guard(where: String, block: suspend () -> Outcome): Outcome = try {
        block()
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (t: Throwable) {   // includes Errors such as OutOfMemoryError / UnsatisfiedLinkError
        CrashReport.record(app, where, t)
        val msg = "Internal error (${t.javaClass.simpleName}: ${t.message}). Your recording was saved if possible. " +
            "Open v2Text → Crash report → Copy, and send it to the developer."
        Outcome(-1, "stt_error", error = msg)
    }

    /** Puts text into the focused field; on failure it is left on the clipboard. */
    fun insertText(text: String): Pair<InsertResult, String?> {
        val svc = V2TextAccessibilityService.instance
        val res = svc?.insert(text, app.settings.blockedPackages) ?: InsertResult.SERVICE_OFF
        if (res != InsertResult.OK) copyToClipboard(text)
        return res to svc?.lastMethod
    }

    /**
     * Inserts a session's clean text into the focused field. Guards against duplicate
     * insertion unless [force] is set by an explicit user action ("Insert again").
     */
    fun insertSession(id: Long, force: Boolean = false): InsertResult {
        val row = app.db.get(id) ?: return InsertResult.FAILED
        if (row.inserted && !force) return InsertResult.ALREADY_INSERTED
        val (res, method) = insertText(row.clean ?: return InsertResult.FAILED)
        if (res == InsertResult.OK) app.db.update(id, status = "ok", inserted = true, insertMethod = method)
        else app.db.update(id, status = "insert_failed", error = res.message)
        return res
    }

    /** Re-run recognition on a session's saved audio. Creates a NEW session; the old one is untouched. */
    suspend fun retry(id: Long): Outcome {
        val row = app.db.get(id) ?: return Outcome(-1, "stt_error", error = "Session not found")
        val f = row.audioPath?.let(::File)?.takeIf { it.exists() }
            ?: return Outcome(id, "stt_error", error = "No saved audio for this session (retention: ${app.settings.retention}).")
        val pcm = Wav.toPcm(Wav.decode(f.readBytes(), Recorder.SR))
        val out = process(pcm, null, row.route, retryOf = id, existingAudio = f)
        if (out.status == "ok" || out.status == "preview" || out.status == "insert_failed") app.db.update(id, clearAudio = true)
        return out
    }

    fun copyToClipboard(text: String) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("v2Text", text))
    }

    fun purgeExpiredAudio(): Int {
        val cutoff = System.currentTimeMillis() - app.settings.retentionDays * 86_400_000L
        return audioDir.listFiles()?.count { it.lastModified() < cutoff && it.delete() } ?: 0
    }

    fun deleteAllAudio(): Int = audioDir.listFiles()?.count { it.delete() } ?: 0

    private fun saveWav(pcm: ShortArray): File {
        val f = File(audioDir, SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".wav")
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(Wav.encode(pcm, Recorder.SR))
        tmp.renameTo(f)          // atomic: never leave a half-written file
        return f
    }

    private fun finalizeAudio(f: File, success: Boolean): File? {
        val keep = when (app.settings.retention) { "always" -> true; "never" -> false; else -> !success }
        if (!keep) { f.delete(); return null }
        return f
    }

    private fun record(v: ContentValues): Long = if (app.settings.saveHistory) app.db.add(v) else -1
}
