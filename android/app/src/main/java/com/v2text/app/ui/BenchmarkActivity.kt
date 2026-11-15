package com.v2text.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.TextView
import com.v2text.app.core.Bench
import com.v2text.app.core.BenchRow
import com.v2text.app.core.Languages
import com.v2text.app.core.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the selected engine over a fixed recording set and reports WER, CER,
 * critical-term accuracy, first/last-word loss and latency (blueprint section 10).
 * References must be human-checked; this screen never generates them.
 */
class BenchmarkActivity : BasePage() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private var tree: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root.p("Put your recordings (16-bit WAV, any sample rate) and a manifest.csv in one folder on the phone, e.g. " +
            "Documents/v2text-bench. manifest.csv columns:\n\naudio,reference,language,critical_terms\n" +
            "clips/en01.wav,\"Send the LNG report by 3 pm.\",en-IN,\"LNG;3 pm\"\n\nlanguage is one of " +
            Languages.PROFILES.joinToString { it.code } + ". Results are written back into the folder as CSV.", 12f, 0.8f)
        root.button("Choose benchmark folder…") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 20)
        }
        root.button("Run with current engine") { run() }
        status = root.p("", 13f, 0.8f)
        result = root.p("", 13f)
        tree = getSharedPreferences("bench", MODE_PRIVATE).getString("tree", null)?.let(Uri::parse)
        status.text = tree?.let { "Folder: ${it.lastPathSegment}" } ?: "No folder chosen."
    }

    @Deprecated("Platform API; fine without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 20 || resultCode != RESULT_OK) return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        tree = uri
        getSharedPreferences("bench", MODE_PRIVATE).edit().putString("tree", uri.toString()).apply()
        status.text = "Folder: ${uri.lastPathSegment}"
    }

    /** Resolve "clips/a.wav" inside the picked tree. */
    private fun resolve(tree: Uri, path: String): Uri? {
        var docId = DocumentsContract.getTreeDocumentId(tree)
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            docId = contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                var found: String? = null
                while (c.moveToNext()) if (c.getString(1) == part) { found = c.getString(0); break }
                found
            } ?: return null
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId)
    }

    private fun run() {
        val t = tree ?: run { status.text = "Choose a folder first."; return }
        val engine = app.engine()
        result.text = ""
        scope.launch {
            val rows = mutableListOf<BenchRow>()
            val err = withContext(Dispatchers.IO) {
                val manifest = resolve(t, "manifest.csv") ?: return@withContext "manifest.csv not found in the folder"
                val items = Bench.parseManifest(contentResolver.openInputStream(manifest)!!.bufferedReader().readText())
                if (items.isEmpty()) return@withContext "manifest.csv has no rows"
                items.forEachIndexed { i, item ->
                    runOnUiThread { status.text = "${i + 1}/${items.size}: ${item.audio}" }
                    val t0 = System.nanoTime()
                    val (hyp, e) = try {
                        val uri = resolve(t, item.audio) ?: throw IllegalStateException("file not found")
                        val raw = Wav.decode(contentResolver.openInputStream(uri)!!.readBytes(), 16000)
                        // same preprocessing as live dictation, so scores reflect real use
                        val trimmed = com.v2text.app.core.AudioPrep.trimSilence(raw)
                        val audio = if (app.settings.boostQuiet) com.v2text.app.core.AudioPrep.normalize(trimmed).audio else trimmed
                        engine.transcribe(audio, Languages.get(item.language), app.settings.dictionary.map { it.term }).text to null
                    } catch (ex: Throwable) {
                        if (ex is kotlinx.coroutines.CancellationException) throw ex
                        com.v2text.app.CrashReport.record(this@BenchmarkActivity, "benchmark ${item.audio}", ex)
                        "" to (ex.message ?: ex.javaClass.simpleName)
                    }
                    rows += Bench.row(item, hyp, (System.nanoTime() - t0) / 1e9, e)
                }
                val model = if (engine.id == "whisper") app.settings.model ?: "?" else "system"
                val name = "results-${engine.id}-$model-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.csv"
                val dir = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
                DocumentsContract.createDocument(contentResolver, dir, "text/csv", name)?.let { out ->
                    contentResolver.openOutputStream(out)?.use { it.write(Bench.toCsv(rows).toByteArray()) }
                }
                null
            }
            status.text = err ?: "Done. Per-clip results saved as CSV in the folder."
            if (rows.isNotEmpty()) result.text = "Engine: ${engine.label}" +
                (if (engine.id == "whisper") " · ${app.settings.model}" else "") + "\n\n" + Bench.summary(rows)
        }
    }
}
