package com.v2text.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.v2text.app.core.Languages
import com.v2text.app.engine.ModelManager
import com.v2text.app.engine.WhisperNative
import com.v2text.app.service.BubbleService
import com.v2text.app.service.V2TextAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BasePage() {
    private lateinit var setupBox: LinearLayout
    private lateinit var engineBox: LinearLayout
    private lateinit var bubbleBtn: android.widget.Button
    private lateinit var progress: TextView
    @Volatile private var cancelDownload = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root.h1("v2Text")
        root.p("Private voice typing. Speech is recognised on this phone; nothing is uploaded.", 14f, 0.8f)

        root.h2("1 · Setup")
        setupBox = root.card()

        root.h2("2 · Speech engine")
        engineBox = root.card()
        progress = root.p("", 13f, 0.8f)

        root.h2("3 · Language")
        val langCard = root.card()
        langCard.choice("Speak in", Languages.PROFILES.map { it.code to it.label }, app.settings.language) {
            app.settings.language = it
        }
        langCard.p("Use one language per dictation for best results. Hinglish and Gujarati accuracy varies by engine " +
            "and model: measure it on the Benchmark screen.", 12f, 0.7f)

        root.h2("4 · Dictate")
        val dict = root.card()
        bubbleBtn = dict.button("Start bubble") { toggleBubble() }
        dict.p("Tap the bubble to start, tap again to stop. Or hold it while you speak. Drag it to move it. " +
            "Works over WhatsApp, Gmail, Samsung Notes and other apps, alongside your normal keyboard.", 12f, 0.7f)
        dict.p("Try it here:", 13f, 0.8f)
        dict.input("Tap here, then use the bubble…", multiLine = true)

        root.h2("More")
        val more = root.card()
        more.button("History, retry & copy") { startActivity(Intent(this, HistoryActivity::class.java)) }
        more.button("Dictionary & snippets") { startActivity(Intent(this, DictionaryActivity::class.java)) }
        more.button("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        more.button("Benchmark (WER / CER)") { startActivity(Intent(this, BenchmarkActivity::class.java)) }
        root.p("\nv2Text ${packageManager.getPackageInfo(packageName, 0).versionName} · whisper.cpp (MIT) · " +
            "Internet is used only when you choose to download a speech model.", 11f, 0.6f)
    }

    override fun onResume() {
        super.onResume()
        renderSetup()
        renderEngine()
        bubbleBtn.text = if (BubbleService.running) "Stop bubble" else "Start bubble"
    }

    // ---------------------------------------------------------------- setup checklist
    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val me = ComponentName(this, V2TextAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun renderSetup() {
        setupBox.removeAllViews()
        fun item(ok: Boolean, title: String, why: String, action: String, onClick: () -> Unit) {
            setupBox.p((if (ok) "✅ " else "⬜ ") + title, 15f)
            if (!ok) { setupBox.p(why, 12f, 0.7f); setupBox.button(action, onClick) }
        }
        item(has(Manifest.permission.RECORD_AUDIO), "Microphone",
            "Used only while the bubble is red (recording).", "Allow microphone") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        item(has(Manifest.permission.POST_NOTIFICATIONS), "Notifications",
            "Android requires a notification while the bubble can use the mic; also used for Retry / Insert actions.",
            "Allow notifications") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
        item(Settings.canDrawOverlays(this), "Display over other apps",
            "Needed to show the floating dictation bubble on top of other apps.", "Open setting") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        item(accessibilityOn(), "Type into other apps (Accessibility)",
            "v2Text uses Accessibility ONLY to put your dictated text into the field you are typing in. It never reads " +
                "other screen content and never touches password fields.\n\nIf the switch is greyed out (\"Restricted " +
                "setting\"): open App info → ⋮ menu (top right) → \"Allow restricted settings\", then try again.",
            "Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (!accessibilityOn()) setupBox.button("Open App info (for restricted settings)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        setupBox.p("Samsung tip: Settings → Battery → Background usage limits → add v2Text to \"Never sleeping apps\" " +
            "so the bubble isn't closed.", 12f, 0.7f)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSetup()
    }

    private fun toggleBubble() {
        if (BubbleService.running) { BubbleService.stop(this); bubbleBtn.text = "Start bubble"; return }
        when {
            !has(Manifest.permission.RECORD_AUDIO) -> toast("Allow the microphone first (Setup)")
            !Settings.canDrawOverlays(this) -> toast("Allow 'Display over other apps' first (Setup)")
            app.settings.engine == "whisper" && app.settings.model == null -> toast("Download or import a Whisper model first")
            else -> {
                if (!accessibilityOn()) toast("Accessibility is off: results will be copied to the clipboard instead of typed")
                BubbleService.start(this); bubbleBtn.text = "Stop bubble"
            }
        }
    }

    // ---------------------------------------------------------------- engine + models
    private fun renderEngine() {
        val s = app.settings
        engineBox.removeAllViews()
        engineBox.choice("Engine", listOf("whisper" to "Whisper (whisper.cpp) · offline", "android" to "Android on-device recognizer"),
            s.engine) { if (it != s.engine) { s.engine = it; app.whisper.release(); renderEngine() } }

        if (s.engine == "whisper") {
            WhisperNative.unavailableReason?.let { engineBox.p("⚠ $it", 13f) }
            engineBox.p("Model in use: ${s.model ?: "none"}", 14f)
            val installed = app.models.installed().map { it.name }.toSet()
            app.models.catalog.forEach { m -> modelRow(m, m.fileName in installed) }
            app.models.installed().filter { f -> app.models.catalog.none { it.fileName == f.name } }.forEach { f ->
                val r = engineBox.row()
                r.addView(TextView(this).apply { text = "${f.name}  (${f.length() / 1_048_576} MiB, imported)"; textSize = 13f },
                    LinearLayout.LayoutParams(0, -2, 2f))
                r.smallButton(if (s.model == f.name) "In use" else "Use") { s.model = f.name; app.whisper.release(); renderEngine() }
                r.smallButton("Delete") { confirmDelete(f) }
            }
            engineBox.button("Import a model file (ggml-*.bin)…") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 10)
            }
            engineBox.p("Start with base. Try small for better Hindi/Gujarati if speed is acceptable. " +
                "Downloads come from huggingface.co (whisper.cpp's official model host).", 12f, 0.7f)
        } else {
            val ok = app.android.isAvailable()
            engineBox.p(if (ok) "✅ On-device recognizer present" else "⚠ This phone reports no on-device recognizer. Use Whisper.", 14f)
            if (ok) {
                val status = engineBox.p("", 13f, 0.8f)
                engineBox.button("Check language availability") {
                    val loc = Languages.get(s.language).androidLocale
                    status.text = "Checking $loc…"
                    scope.launch {
                        val r = app.android.checkSupport(loc)
                        status.text = if (r.error != null) "Could not query languages (error ${r.error})."
                        else "Installed: ${r.installed.joinToString().ifEmpty { "none" }}\nDownloadable: " +
                            "${r.supported.joinToString().ifEmpty { "none" }}\nPending: ${r.pending.joinToString().ifEmpty { "none" }}"
                    }
                }
                engineBox.button("Download language pack for ${Languages.get(s.language).androidLocale}") {
                    app.android.requestModelDownload(Languages.get(s.language).androidLocale)
                }
                engineBox.p("This engine is provided by your phone (Google/Samsung). v2Text feeds it its own recording so " +
                    "audio can be kept for retry. Benchmark it before relying on it.", 12f, 0.7f)
            }
        }
    }

    private fun modelRow(m: ModelManager.ModelInfo, installed: Boolean) {
        val s = app.settings
        val r = engineBox.row()
        r.addView(TextView(this).apply { text = "${m.name}\n${m.note}"; textSize = 13f }, LinearLayout.LayoutParams(0, -2, 2f))
        if (installed) {
            r.smallButton(if (s.model == m.fileName) "In use" else "Use") { s.model = m.fileName; app.whisper.release(); renderEngine() }
            r.smallButton("Delete") { app.models.file(m.fileName)?.let(::confirmDelete) }
        } else r.smallButton("Download") { confirmDownload(m) }
    }

    private fun confirmDelete(f: java.io.File) = alert("Delete ${f.name}?", "You can download it again later.", "Delete") {
        if (app.settings.model == f.name) { app.whisper.release(); app.settings.model = null }
        app.models.delete(f); renderEngine()
    }

    private fun confirmDownload(m: ModelManager.ModelInfo) {
        progress.text = "Checking size of ${m.fileName}…"
        scope.launch {
            val size = withContext(Dispatchers.IO) { runCatching { app.models.remoteSize(m) }.getOrDefault(-1L) }
            val sz = if (size > 0) "${size / 1_048_576} MiB" else "unknown size"
            progress.text = ""
            alert("Download ${m.fileName}?", "$sz from huggingface.co. Use Wi-Fi. This is the only time v2Text uses the " +
                "internet. ${if (m.sha1 != null) "The file is checked against its published SHA-1." else
                    "No published checksum: the file is checked by loading it."}", "Download") { download(m) }
        }
    }

    private fun download(m: ModelManager.ModelInfo) {
        cancelDownload = false
        progress.text = "Downloading ${m.fileName}…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.models.download(m, { done, total ->
                        runOnUiThread {
                            progress.text = "Downloading ${m.fileName}: ${done / 1_048_576} / " +
                                (if (total > 0) "${total / 1_048_576} MiB" else "? MiB")
                        }
                    }, { cancelDownload || isDestroyed })
                }
            }
            result.onSuccess { f ->
                if (app.settings.model == null) app.settings.model = f.name
                progress.text = "✅ ${f.name} installed"
            }.onFailure { progress.text = "❌ ${it.message}" }
            renderEngine()
        }
    }

    @Deprecated("Platform API; fine without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 10 || resultCode != RESULT_OK) return
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        progress.text = "Importing ${name ?: "model"}…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { app.models.import(uri, name) } }
            r.onSuccess { f -> if (app.settings.model == null) app.settings.model = f.name; progress.text = "✅ Imported ${f.name}" }
                .onFailure { progress.text = "❌ ${it.message}" }
            renderEngine()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
