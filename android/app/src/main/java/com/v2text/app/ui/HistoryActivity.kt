package com.v2text.app.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import com.v2text.app.data.SessionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Verbatim vs clean text, diagnostics, and the separate Retry-transcription / Copy actions. */
class HistoryActivity : BasePage() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root.p(app.db.stats(), 12f, 0.75f)
        val r = root.row()
        r.smallButton("Delete all saved audio") {
            alert("Delete all saved audio?", "Retry will no longer be possible for past sessions.", "Delete") {
                val n = app.controller.deleteAllAudio(); toast("Deleted $n recordings"); render()
            }
        }
        r.smallButton("Clear history") {
            alert("Clear all history?", "Transcripts are deleted from this phone. Saved audio is deleted too.", "Clear") {
                app.controller.deleteAllAudio(); app.db.clear(); render()
            }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val rows = app.db.recent(100)
        if (rows.isEmpty()) list.p("\nNo dictations yet.", 14f, 0.7f)
        rows.forEach(::card)
    }

    private fun card(s: SessionRow) {
        val c = list.card()
        val icon = when (s.status) { "ok" -> "✅"; "preview" -> "👁"; "no_speech", "too_short" -> "🔇"; "blocked" -> "⛔"; else -> "⚠" }
        c.p("$icon #${s.id} · ${s.status} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.ts))}", 13f)
        c.p(listOfNotNull(s.engine?.let { "${it}/${s.model ?: "?"}" }, s.language, s.pkg, s.route,
            if (s.sttSeconds > 0) "%.1fs audio → %.2fs".format(s.audioSeconds, s.sttSeconds) else null,
            if (s.startLatencyMs >= 0) "mic start ${s.startLatencyMs} ms" else null,
            s.retryOf?.let { "retry of #$it" }).joinToString(" · "), 11f, 0.6f)
        s.clean?.let { c.p(it, 16f) }
        if (s.verbatim != null && s.verbatim != s.clean) c.p("Verbatim: ${s.verbatim}", 12f, 0.65f)
        s.changes?.takeIf { it.isNotBlank() }?.let { c.p("Formatter: $it", 11f, 0.55f) }
        s.error?.let { c.p(it, 12f, 0.8f) }
        s.diagnostics?.let { d ->
            runCatching { JSONObject(d).getJSONArray("warnings") }.getOrNull()?.let { w ->
                for (i in 0 until w.length()) c.p("⚠ ${w.getString(i)}", 11f, 0.7f)
            }
        }
        val r = c.row()
        if (s.clean != null) r.smallButton("Copy") { app.controller.copyToClipboard(s.clean); toast("Copied") }
        if (s.verbatim != null && s.verbatim != s.clean) r.smallButton("Copy verbatim") {
            app.controller.copyToClipboard(s.verbatim); toast("Copied verbatim")
        }
        if (s.audioPath != null && File(s.audioPath).exists()) r.smallButton("Retry") {
            toast("Re-transcribing #${s.id}…")
            scope.launch {
                val out = withContext(Dispatchers.Default) { app.controller.retrySafely(s.id) }
                toast(if (out.clean.isNotEmpty()) "#${out.id}: ${out.clean}" else "${out.status}: ${out.error ?: ""}")
                render()
            }
        }
        r.smallButton("Delete") {
            s.audioPath?.let { File(it).delete() }; app.db.delete(s.id); render()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
