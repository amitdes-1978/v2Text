package com.v2text.app.core

/**
 * Removes well-known Whisper failure modes from the *clean* text (the verbatim transcript is
 * never changed):
 *  - non-speech annotations: "[Music]", "(upbeat music)", "[BLANK_AUDIO]", "♪♪"
 *  - training-data outros Whisper invents on silence: "Thanks for watching", "Please subscribe",
 *    "Subtitles by the Amara.org community"
 *  - decoder loops: the same phrase repeated 3+ times in a row ("I will go. I will go. I will go.")
 *
 * Deliberately conservative: a bare "Thank you." is kept because people do say it.
 */
object TranscriptFilter {
    data class Result(val text: String, val changes: List<String>)

    private val I = setOf(RegexOption.IGNORE_CASE)

    val nonSpeech = Regex(
        """[\[(][^\])]{0,40}(?:music|applause|laughter|silence|blank_audio|noise|inaudible|beep|sigh|cough|""" +
            """upbeat|static|background|सरगम|संगीत|સંગીત)[^\])]{0,40}[\])]|[♪♫]+""", I)

    val outros = Regex(
        """[^.!?\n]*(?:thanks? (?:you )?(?:so much )?for watching|please (?:like and )?subscribe|like and subscribe|""" +
            """subtitles by|amara\.org|see you (?:in the|next) (?:next )?video)[^.!?\n]*[.!?]?""", I)

    fun clean(input: String): Result {
        val changes = mutableListOf<String>()
        var t = input
        nonSpeech.replace(t, " ").let { if (it != t) { changes += "non_speech_tags"; t = it } }
        outros.replace(t, " ").let { if (it != t) { changes += "hallucinated_outro"; t = it } }
        collapseLoops(t).let { if (it != t) { changes += "repetition_loop"; t = it } }
        return Result(t.replace(Regex("""\s+"""), " ").trim(), changes)
    }

    /** Collapse a phrase of 2..8 words repeated 3+ times consecutively, or one word repeated 4+ times. */
    fun collapseLoops(text: String): String {
        val tok = text.split(' ').filter { it.isNotEmpty() }
        if (tok.size < 4) return text
        fun norm(s: String) = s.lowercase().trim { !it.isLetterOrDigit() }
        val out = ArrayList<String>(tok.size)
        var i = 0
        while (i < tok.size) {
            var skipped = false
            for (n in 8 downTo 1) {
                if (i + n * 2 > tok.size) continue
                val unit = (0 until n).map { norm(tok[i + it]) }
                if (unit.any { it.isEmpty() }) continue
                var reps = 1
                while (i + (reps + 1) * n <= tok.size && (0 until n).all { norm(tok[i + reps * n + it]) == unit[it] }) reps++
                if (reps >= (if (n == 1) 4 else 3)) {
                    out.addAll(tok.subList(i + (reps - 1) * n, i + reps * n))  // keep the last copy (keeps its punctuation)
                    i += reps * n
                    skipped = true
                    break
                }
            }
            if (!skipped) { out += tok[i]; i++ }
        }
        return out.joinToString(" ")
    }

    fun patternsForCompatCheck() = listOf(nonSpeech.pattern, outros.pattern)
}
