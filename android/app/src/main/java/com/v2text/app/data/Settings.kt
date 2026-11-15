package com.v2text.app.data

import android.content.Context
import com.v2text.app.core.CleanOptions
import com.v2text.app.core.DictEntry
import com.v2text.app.core.Languages
import org.json.JSONArray
import org.json.JSONObject

/** All settings, stored locally in SharedPreferences. Privacy-first defaults. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("v2text", Context.MODE_PRIVATE)

    var engine: String get() = p.getString("engine", "whisper")!!; set(v) = p.edit().putString("engine", v).apply()
    var model: String? get() = p.getString("model", null); set(v) = p.edit().putString("model", v).apply()
    var language: String get() = p.getString("language", "en-IN")!!; set(v) = p.edit().putString("language", v).apply()
    var threads: Int get() = p.getInt("threads", 4); set(v) = p.edit().putInt("threads", v).apply()
    var beamSize: Int get() = p.getInt("beam", 1); set(v) = p.edit().putInt("beam", v).apply()

    var removeFillers: Boolean get() = p.getBoolean("fillers", true); set(v) = p.edit().putBoolean("fillers", v).apply()
    var selfCorrections: Boolean get() = p.getBoolean("selfcorr", true); set(v) = p.edit().putBoolean("selfcorr", v).apply()
    var formatLists: Boolean get() = p.getBoolean("lists", true); set(v) = p.edit().putBoolean("lists", v).apply()

    /** Keeps the mic open while the bubble is visible so the first word is never clipped. */
    var preroll: Boolean get() = p.getBoolean("preroll", false); set(v) = p.edit().putBoolean("preroll", v).apply()
    var prerollMs: Int get() = p.getInt("preroll_ms", 400); set(v) = p.edit().putInt("preroll_ms", v).apply()
    var tailMs: Int get() = p.getInt("tail_ms", 300); set(v) = p.edit().putInt("tail_ms", v).apply()
    /** Digital gain for quiet input (Samsung's raw speech mic is low-gain). Saved audio is never changed. */
    /** Opt-in: use text before the cursor (stays on the phone) to prime recognition and continue sentences. */
    var useContext: Boolean get() = p.getBoolean("context", false); set(v) = p.edit().putBoolean("context", v).apply()
    var boostQuiet: Boolean get() = p.getBoolean("boost", true); set(v) = p.edit().putBoolean("boost", v).apply()
    var minMs: Int get() = p.getInt("min_ms", 400); set(v) = p.edit().putInt("min_ms", v).apply()

    /** auto = insert straight away; preview = show the result first, insert on tap. */
    var insertMode: String get() = p.getString("insert", "auto")!!; set(v) = p.edit().putString("insert", v).apply()
    /** never | on_failure | always */
    var retention: String get() = p.getString("retention", "on_failure")!!; set(v) = p.edit().putString("retention", v).apply()
    var retentionDays: Int get() = p.getInt("retention_days", 14); set(v) = p.edit().putInt("retention_days", v).apply()
    var saveHistory: Boolean get() = p.getBoolean("history", true); set(v) = p.edit().putBoolean("history", v).apply()

    var blockedPackages: List<String>
        get() = p.getString("blocked", null)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: DEFAULT_BLOCKED
        set(v) = p.edit().putString("blocked", v.joinToString(",")).apply()

    var dictionary: List<DictEntry>
        get() {
            val s = p.getString("dictionary", null) ?: return STARTER_DICTIONARY
            val a = JSONArray(s)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val al = o.optJSONArray("aliases") ?: JSONArray()
                DictEntry(o.getString("term"), (0 until al.length()).map { al.getString(it) }, o.optString("category", "general"))
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { e -> a.put(JSONObject().put("term", e.term).put("aliases", JSONArray(e.aliases)).put("category", e.category)) }
            p.edit().putString("dictionary", a.toString()).apply()
        }

    var snippets: Map<String, String>
        get() {
            val o = JSONObject(p.getString("snippets", "{}")!!)
            return o.keys().asSequence().associateWith { o.getString(it) }
        }
        set(v) = p.edit().putString("snippets", JSONObject(v).toString()).apply()

    var onboarded: Boolean get() = p.getBoolean("onboarded", false); set(v) = p.edit().putBoolean("onboarded", v).apply()

    fun cleanOptions() = CleanOptions(
        fillers = Languages.get(language).fillers,
        aliases = dictionary.flatMap { e -> e.aliases.filter(String::isNotBlank).map { it to e.term } }.toMap(),
        terms = dictionary.map { it.term },
        snippets = snippets,
        removeFillers = removeFillers, selfCorrections = selfCorrections, lists = formatLists,
    )

    companion object {
        /** Never transcribe into / paste into these (password managers, banking, payments). */
        val DEFAULT_BLOCKED = listOf(
            "com.x8bit.bitwarden", "com.onepassword.android", "com.lastpass.lpandroid", "com.agilebits.onepassword",
            "keepass2android.keepass2android", "com.kunzisoft.keepass.free", "com.samsung.android.samsungpass",
            "com.sbi.lotusintouch", "com.snapwork.hdfc", "com.csam.icici.bank.imobile", "com.axis.mobile",
            "net.one97.paytm", "com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "in.org.npci.upiapp",
        )
        val STARTER_DICTIONARY = listOf(
            DictEntry("LNG", listOf("l n g", "el en gee"), "domain"),
            DictEntry("UGEL", listOf("u gel", "you gel"), "domain"),
            DictEntry("Retail Outlet", emptyList(), "domain"),
            DictEntry("Command Centre", listOf("command center"), "domain"),
        )
    }
}
