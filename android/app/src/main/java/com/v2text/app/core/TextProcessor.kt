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

    // Android's regex engine is ICU, which rejects Java's (?U) / UNICODE_CHARACTER_CLASS flag (that caused
    // "NoClassDefFoundError: TextProcessor" on the phone). Spell out Unicode word characters explicitly:
    // letters, combining marks (Devanagari/Gujarati vowel signs), digits and underscore. Same result on JVM and ICU.
    const val WORD = """[\p{L}\p{M}\p{N}_]"""
    const val NON_WORD = """[^\p{L}\p{M}\p{N}_]"""

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
        return fillerRegex(alt).replace(text, "")
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
            item = item.replace(TIDY[6], "").trimEnd('.')
            item.replaceFirstChar { it.uppercaseChar() }
        }
        val body = items.mapIndexed { i, it -> "${i + 1}. $it" }.joinToString("\n")
        if (intro.isNotEmpty() && !intro.endsWith(":")) intro = intro.trimEnd('.') + ":"
        return if (intro.isNotEmpty()) "$intro\n$body" else body
    }

    private val shortOrNumeric = Regex("""(?:\d|$NON_WORD)+""")

    /** Exact (case-insensitive, whole-word) alias -> preferred term. No fuzzy/phonetic matching. */
    fun applyAliases(input: String, aliases: Map<String, String>): String {
        var text = input
        for (alias in aliases.keys.sortedByDescending { it.length }) {
            if (alias.length < 3 || shortOrNumeric.matches(alias)) continue
            val term = aliases.getValue(alias)
            text = wholeWord(alias).replace(text) { term }
        }
        return text
    }

    /** Force canonical casing ("lng" -> "LNG") for dictionary terms. */
    fun applyTermCase(input: String, terms: List<String>): String {
        var text = input
        for (t in terms.sortedByDescending { it.length }) {
            text = wholeWord(t).replace(text) { t }
        }
        return text
    }

    fun protectSnippets(input: String, snippets: Map<String, String>): Pair<String, Map<String, String>> {
        var text = input
        val mapping = linkedMapOf<String, String>()
        snippets.keys.sortedByDescending { it.length }.forEachIndexed { i, trig ->
            val tok = "[[S$i]]"
            val new = snippetRegex(trig).replace(text) { tok }
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

    private val TIDY = listOf(Regex("""[ \t]+"""), Regex(""" +([,.!?;:])"""), Regex(""",\s*([.!?])"""),
        Regex("""([.!?])\1+"""), Regex("""^[\s,]+"""), Regex("""[ \t]*\n[ \t]*"""), Regex("""\s+and$"""))

    fun tidy(input: String): String {
        var t = input
        t = t.replace(TIDY[0], " ")
        t = t.replace(TIDY[1]) { it.groupValues[1] }
        t = t.replace(TIDY[2]) { it.groupValues[1] }
        t = t.replace(TIDY[3]) { it.groupValues[1] }
        t = t.replace(TIDY[4], "")
        t = t.replace(TIDY[5], "\n").trim()
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
        step("repeated_words", ::removeRepeatedWords)
        step("scratch_that", ::applyScratchThat)
        if (o.selfCorrections) step("self_correction", ::applySelfCorrections)
        step("spoken_commands", ::applySpokenCommands)
        if (o.lists) step("list", ::formatLists)
        step("term_case") { applyTermCase(it, o.terms) }
        step("tidy", ::tidy)
        return CleanResult(unmask(text, snipMap), changes)
    }

    private val wordRe = Regex("""[\p{L}\p{M}\p{N}_'-]+""")
    fun wordCount(text: String): Int = wordRe.findAll(text).count()

    // Stutters: "the the", "I I". Kept: numbers, and words that legitimately double ("that that", "had had").
    private val repeatRe = Regex("""(?<!$WORD)($WORD+)(?:\s+\1)+(?!$WORD)""", I)
    private val keepDoubled = setOf("that", "had", "is", "do", "very", "bye", "no", "yes", "ha", "hi")

    fun removeRepeatedWords(text: String): String = repeatRe.replace(text) { m ->
        val w = m.groupValues[1]
        if (w.lowercase() in keepDoubled || w.any { it.isDigit() }) m.value else w
    }

    private val sentenceEnders = setOf('.', '!', '?', '\n', '।', '॥')
    private val iForms = Regex("""^I(?:'m|'ll|'ve|'d)?$""")

    /**
     * Context continuation: when the text before the cursor ends mid-sentence, don't start the
     * dictation with a capital letter (Whisper always capitalises). Kept capitalised: "I", acronyms
     * (two or more capitals), and dictionary terms.
     */
    fun continueSentence(clean: String, contextBefore: String?, terms: List<String> = emptyList()): String {
        val ctx = contextBefore?.trimEnd(' ', '\t') ?: return clean
        if (ctx.isEmpty() || ctx.last() in sentenceEnders || clean.isEmpty()) return clean
        val first = clean.takeWhile { !it.isWhitespace() }.trimEnd(',', '.', '!', '?', ';', ':')
        if (first.isEmpty() || !first[0].isUpperCase()) return clean
        if (iForms.matches(first) || first.count { it.isUpperCase() } >= 2 || terms.any { it == first || it.startsWith("$first ") }) return clean
        return clean[0].lowercaseChar() + clean.substring(1)
    }

    // ---------------------------------------------------------------- pattern builders
    private fun fillerRegex(alt: String) =
        Regex("""(?:,\s*)?(?<![\p{L}\p{M}\p{N}_'])(?:$alt)(?:\s*,)?(?![\p{L}\p{M}\p{N}_'])""", I)
    private fun wholeWord(literal: String) = Regex("""(?<!$WORD)${Regex.escape(literal)}(?!$WORD)""", I)
    private fun snippetRegex(trig: String) = Regex("""(?<!$WORD)${Regex.escape(trig)}(?!$WORD)[.!?]?""", I)

    /**
     * Every pattern this object compiles (dynamic ones built from sample input), so tests can
     * verify them against Android's ICU regex engine, not just the JVM's.
     */
    fun patternsForCompatCheck(): List<String> = listOf(
        protectedRegex.pattern, fillerRegex("um|उम्म|અં").pattern, scratchRe.pattern, sentenceSplit.pattern,
        corrRe.pattern, timeRe.pattern, ordRe.pattern, shortOrNumeric.pattern, wholeWord("l n g").pattern,
        snippetRegex("my signature").pattern, sentenceStart.pattern, wordRe.pattern, repeatRe.pattern, iForms.pattern,
    ) + TranscriptFilter.patternsForCompatCheck() + spoken.map { it.first.pattern } + TIDY.map { it.pattern }
}
