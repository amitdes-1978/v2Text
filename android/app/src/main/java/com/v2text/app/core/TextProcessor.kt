package com.v2text.app.core

/**
 * Deterministic, auditable text formatter (Kotlin port of v2text/textproc.py).
 *
 * The verbatim transcript is never modified: every step returns a new string
 * and the names of steps that changed something are recorded, so formatting
 * cannot hide recognition errors.
 */
data class DictEntry(val term: String, val aliases: List<String> = emptyList(), val category: String = "general")

data class CleanResult(val text: String, val changes: List<String>)

data class CleanOptions(
    val fillers: List<String> = Languages.COMMON_FILLERS,
    val aliases: Map<String, String> = emptyMap(),
    val terms: List<String> = emptyList(),
    val snippets: Map<String, String> = emptyMap(),
    val removeFillers: Boolean = true,
    val selfCorrections: Boolean = true,
    val lists: Boolean = true,
)

object TextProcessor {
    private val I = setOf(RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------ protected tokens
    // Anything matching these must leave the formatter unchanged.
    private val PROTECTED = listOf(
        """[\w.+-]+@[\w-]+\.[\w.]+""",                                         // email
        """https?://\S+|www\.\S+""",                                              // url
        """\b[A-Za-z]{2}[ -]?\d{1,2}[ -]?[A-Za-z]{1,3}[ -]?\d{4}\b""",            // vehicle reg: GJ 01 AB 1234
        """\b\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}\b""",                                 // date
        """\b\d{1,2}(?::\d{2})?\s?[ap]\.?m\.?(?!\w)""",                            // time
        """\b\d{1,2}:\d{2}\b""",                                                    // 24h time
        """(?:₹|rs\.?\s?|inr\s?|\$)\s?\d[\d,]*(?:\.\d+)?""",                        // amount
        """\b\d[\d,]*(?:\.\d+)?\s?(?:%|kg|km|kl|mt|tonnes?|litres?|liters?|l)\b""", // measure
        """\b\d[\d,]*(?:\.\d+)?\b""",                                               // number
    )
    val protectedRegex = Regex(PROTECTED.joinToString("|") { "(?:$it)" }, I)

    fun findProtected(text: String): List<String> = protectedRegex.findAll(text).map { it.value }.toList()

    // ------------------------------------------------------------ steps
    fun removeFillers(text: String, fillers: List<String>): String {
        if (fillers.isEmpty()) return text
        val alt = fillers.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        return Regex("""(?U)(?:,\s*)?(?<![\w'])(?:$alt)(?:\s*,)?(?![\w'])""", I).replace(text, "")
    }

    private val scratchRe = Regex("""[.!?,]?\s*\bscratch that\b[.!?,]?\s*""", I)
    private val sentenceSplit = Regex("""(?<=[.!?])\s+""")

    /** "... at 5. Scratch that. ... at 6." -> drops the sentence before the command. */
    fun applyScratchThat(input: String): String {
        var text = input
        while (true) {
            val m = scratchRe.find(text) ?: return text
            val before = text.substring(0, m.range.first)
            val after = text.substring(m.range.last + 1)
            val kept = sentenceSplit.split(before.trim()).dropLast(1).joinToString(" ")
            text = if (kept.isNotEmpty()) "$kept $after".trim() else after.trim()
        }
    }

    // Explicit cue + same kind of value (time->time, number->number) only.
    private const val VAL = """(?:\b\d{1,2}(?::\d{2})?\s?[ap]\.?m\.?|(?:₹\s?)?\b\d[\d,]*(?:\.\d+)?)"""
    private val corrRe = Regex(
        """($VAL)(\s?[a-z%]*)\s*,?\s*(?:actually|no wait|sorry|i mean|correction)\s*,?\s*($VAL)""", I)
    private val timeRe = Regex("""[ap]\.?m""", I)
    private fun kind(v: String) = if (timeRe.containsMatchIn(v)) "time" else "num"

    fun applySelfCorrections(text: String): String = corrRe.replace(text) { m ->
        val old = m.groupValues[1]
        val new = m.groupValues[3]
        if (kind(old) != kind(new)) m.value else new
    }

    private val spoken = listOf(
        Regex("""\s*\bnew paragraph\b[.,]?\s*""", I) to "\n\n",
        Regex("""\s*\bnew line\b[.,]?\s*""", I) to "\n",
    )

    fun applySpokenCommands(text: String): String =
        spoken.fold(text) { t, (rx, rep) -> rx.replace(t) { rep } }

    private val ORD = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")
    private val ordRe = Regex("""(?:(?<=^)|(?<=[\s.,;:]))(${ORD.joinToString("|")})(?:ly)?\b[,:]?\s+""", I)

    /** "agenda first budget, second hiring, third travel" -> numbered list (>= 2 ordered items). */
    fun formatLists(text: String): String {
        val seq = mutableListOf<MatchResult>()
        for (m in ordRe.findAll(text)) {
            if (seq.size < ORD.size && m.groupValues[1].lowercase() == ORD[seq.size]) seq += m
        }
        if (seq.size < 2) return text
        var intro = text.substring(0, seq[0].range.first).trim().trimEnd(',', ';')
        val items = seq.mapIndexed { i, m ->
            val end = if (i + 1 < seq.size) seq[i + 1].range.first else text.length
            var item = text.substring(m.range.last + 1, end).trim().trim(',', ';').trim()
            item = item.replace(Regex("""\s+and$"""), "").trimEnd('.')
            item.replaceFirstChar { it.uppercaseChar() }
        }
        val body = items.mapIndexed { i, it -> "${i + 1}. $it" }.joinToString("\n")
        if (intro.isNotEmpty() && !intro.endsWith(":")) intro = intro.trimEnd('.') + ":"
        return if (intro.isNotEmpty()) "$intro\n$body" else body
    }

    private val shortOrNumeric = Regex("""(?U)[\d\W]+""")

    /** Exact (case-insensitive, whole-word) alias -> preferred term. No fuzzy/phonetic matching. */
    fun applyAliases(input: String, aliases: Map<String, String>): String {
        var text = input
        for (alias in aliases.keys.sortedByDescending { it.length }) {
            if (alias.length < 3 || shortOrNumeric.matches(alias)) continue
            val term = aliases.getValue(alias)
            text = Regex("""(?U)(?<!\w)${Regex.escape(alias)}(?!\w)""", I).replace(text) { term }
        }
        return text
    }

    /** Force canonical casing ("lng" -> "LNG") for dictionary terms. */
    fun applyTermCase(input: String, terms: List<String>): String {
        var text = input
        for (t in terms.sortedByDescending { it.length }) {
            text = Regex("""(?U)(?<!\w)${Regex.escape(t)}(?!\w)""", I).replace(text) { t }
        }
        return text
    }

    fun protectSnippets(input: String, snippets: Map<String, String>): Pair<String, Map<String, String>> {
        var text = input
        val mapping = linkedMapOf<String, String>()
        snippets.keys.sortedByDescending { it.length }.forEachIndexed { i, trig ->
            val tok = "[[S$i]]"
            val new = Regex("""(?U)(?<!\w)${Regex.escape(trig)}(?!\w)[.!?]?""", I).replace(text) { tok }
            if (new != text) {
                mapping[tok] = snippets.getValue(trig)
                text = new
            }
        }
        return text to mapping
    }

    fun unmask(input: String, mapping: Map<String, String>): String =
        mapping.entries.fold(input) { t, (k, v) -> t.replace(k, v) }

    private val sentenceStart = Regex("""(^|[.!?]\s+|\n)([a-z])""")

    fun tidy(input: String): String {
        var t = input
        t = t.replace(Regex("""[ \t]+"""), " ")
        t = t.replace(Regex(""" +([,.!?;:])""")) { it.groupValues[1] }
        t = t.replace(Regex(""",\s*([.!?])""")) { it.groupValues[1] }
        t = t.replace(Regex("""([.!?])\1+""")) { it.groupValues[1] }
        t = t.replace(Regex("""^[\s,]+"""), "")
        t = t.replace(Regex("""[ \t]*\n[ \t]*"""), "\n").trim()
        t = sentenceStart.replace(t) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return t
    }

    fun clean(raw: String, o: CleanOptions = CleanOptions()): CleanResult {
        var text = raw
        val changes = mutableListOf<String>()
        fun step(name: String, fn: (String) -> String) {
            val new = fn(text)
            if (new != text) {
                changes += name
                text = new
            }
        }
        step("aliases") { applyAliases(it, o.aliases) }
        val (masked, snipMap) = protectSnippets(text, o.snippets)
        if (snipMap.isNotEmpty()) changes += "snippets"
        text = masked
        if (o.removeFillers) step("fillers") { removeFillers(it, o.fillers) }
        step("scratch_that", ::applyScratchThat)
        if (o.selfCorrections) step("self_correction", ::applySelfCorrections)
        step("spoken_commands", ::applySpokenCommands)
        if (o.lists) step("list", ::formatLists)
        step("term_case") { applyTermCase(it, o.terms) }
        step("tidy", ::tidy)
        return CleanResult(unmask(text, snipMap), changes)
    }

    fun wordCount(text: String): Int = Regex("""(?U)[\w'-]+""").findAll(text).count()
}
