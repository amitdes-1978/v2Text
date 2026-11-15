package com.v2text.app.ui

import android.os.Bundle

class SettingsActivity : BasePage() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = app.settings

        root.h2("Formatting (applied to the clean text only)")
        val f = root.card()
        f.toggle("Remove filler sounds (um, uh, hmm)", s.removeFillers) { s.removeFillers = it }
        f.toggle("Apply explicit corrections (\"4 pm, actually 3 pm\")", s.selfCorrections) { s.selfCorrections = it }
        f.toggle("Format spoken lists (first…, second…)", s.formatLists) { s.formatLists = it }
        f.p("Numbers, times, dates, ₹ amounts, vehicle numbers (GJ 01 AB 1234), emails and links are never changed. " +
            "The verbatim transcript is always kept in History.", 12f, 0.7f)

        root.h2("Inserting text")
        val i = root.card()
        i.choice("After dictation", listOf("auto" to "Type it into the field immediately", "preview" to
            "Show it first (Insert from the notification)"), s.insertMode) { s.insertMode = it }

        root.h2("Microphone")
        val m = root.card()
        m.toggle("Pre-roll: keep mic open while the bubble is shown", s.preroll) { s.preroll = it }
        m.p("Protects the first word if you start speaking as you tap. Android's green mic indicator stays on while " +
            "the bubble is visible. Restart the bubble after changing.", 12f, 0.7f)
        m.choice("Pre-roll length", listOf(300 to "300 ms", 400 to "400 ms", 500 to "500 ms"), s.prerollMs) { s.prerollMs = it }
        m.choice("Keep recording after you stop", listOf(0 to "0 ms", 150 to "150 ms", 300 to "300 ms", 500 to "500 ms"),
            s.tailMs) { s.tailMs = it }
        m.toggle("Boost quiet speech before recognition", s.boostQuiet) { s.boostQuiet = it }
        m.p("Raises low microphone levels (up to +24 dB) on the copy sent to the speech engine. Recordings are " +
            "saved unchanged. Compare on/off on the Benchmark screen.", 12f, 0.7f)
        m.choice("Ignore taps shorter than", listOf(250 to "250 ms", 400 to "400 ms", 600 to "600 ms"), s.minMs) { s.minMs = it }

        root.h2("Whisper engine")
        val w = root.card()
        w.choice("CPU threads", listOf(2 to "2", 3 to "3", 4 to "4 (A52s big cores)", 6 to "6", 8 to "8"), s.threads) {
            s.threads = it
        }
        w.choice("Decoding", listOf(1 to "Greedy (fastest)", 3 to "Beam search 3", 5 to "Beam search 5 (slowest)"), s.beamSize) {
            s.beamSize = it
        }

        root.h2("Privacy")
        val p = root.card()
        p.choice("Keep recordings", listOf("never" to "Never", "on_failure" to "Only when recognition fails (for Retry)",
            "always" to "Always (for benchmarking)"), s.retention) { s.retention = it }
        p.choice("Delete kept recordings after", listOf(1 to "1 day", 7 to "7 days", 14 to "14 days", 30 to "30 days"),
            s.retentionDays) { s.retentionDays = it }
        p.toggle("Save transcript history on this phone", s.saveHistory) { s.saveHistory = it }
        p.p("Never dictate into these apps (package names, comma-separated):", 13f, 0.8f)
        val blocked = p.input("package names", s.blockedPackages.joinToString(", "), multiLine = true)
        p.button("Save blocked apps") {
            s.blockedPackages = blocked.text.split(',').map(String::trim).filter(String::isNotEmpty)
            android.widget.Toast.makeText(this, "Saved", android.widget.Toast.LENGTH_SHORT).show()
        }
        p.p("Password fields are always skipped, whatever this list says.", 12f, 0.7f)
    }
}
