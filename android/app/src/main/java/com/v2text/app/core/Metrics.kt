package com.v2text.app.core

import java.text.Normalizer
import java.util.Locale

/** WER / CER / critical-term accuracy / boundary loss (blueprint section 10.2). */
object Metrics {
    fun normalize(text: String): String {
        val t = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC)
        val sb = StringBuilder()
        for (ch in t) {
            val type = Character.getType(ch)
            val punct = type == Character.CONNECTOR_PUNCTUATION.toInt() || type == Character.DASH_PUNCTUATION.toInt() ||
                type == Character.START_PUNCTUATION.toInt() || type == Character.END_PUNCTUATION.toInt() ||
                type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.OTHER_PUNCTUATION.toInt()
            sb.append(if (punct) ' ' else ch)
        }
        return sb.toString().replace(Regex("""\s+"""), " ").trim()
    }

    fun <T> editDistance(a: List<T>, b: List<T>): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1)
            cur[0] = i
            for (j in 1..b.size) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.size]
    }

    private fun words(s: String) = normalize(s).split(' ').filter { it.isNotEmpty() }
    private fun chars(s: String) = normalize(s).replace(" ", "").toList()

    fun wordCount(text: String): Int = words(text).size
    fun wordErrors(ref: String, hyp: String): Int = editDistance(words(ref), words(hyp))
    fun characterCount(text: String): Int = chars(text).size
    fun characterErrors(ref: String, hyp: String): Int = editDistance(chars(ref), chars(hyp))

    fun wer(ref: String, hyp: String): Double = wordErrors(ref, hyp).toDouble() / maxOf(wordCount(ref), 1)
    fun cer(ref: String, hyp: String): Double = characterErrors(ref, hyp).toDouble() / maxOf(characterCount(ref), 1)

    /** Match whole normalized token sequences; substring matches (e.g. LNG inside BLNG) are not hits. */
    private fun containsPhrase(haystack: List<String>, phrase: List<String>): Boolean {
        if (phrase.isEmpty() || phrase.size > haystack.size) return false
        return haystack.windowed(phrase.size).any { it == phrase }
    }

    fun criticalHits(terms: List<String>, hyp: String): Pair<Int, Int> {
        val actual = words(hyp)
        val expected = terms.filter { it.isNotBlank() }.map(::words).filter { it.isNotEmpty() }
        return expected.count { containsPhrase(actual, it) } to expected.size
    }

    fun firstWordLost(ref: String, hyp: String): Boolean {
        val r = words(ref); val h = words(hyp)
        return r.isNotEmpty() && (h.isEmpty() || h.first() != r.first())
    }

    fun lastWordLost(ref: String, hyp: String): Boolean {
        val r = words(ref); val h = words(hyp)
        return r.isNotEmpty() && (h.isEmpty() || h.last() != r.last())
    }
}

data class BenchRow(
    val audio: String, val language: String, val reference: String, val hypothesis: String,
    val wer: Double, val cer: Double, val criticalHit: Int, val criticalTotal: Int,
    val firstWordLost: Boolean, val lastWordLost: Boolean, val latencyS: Double, val error: String?,
)

object Bench {
    data class Item(val audio: String, val reference: String, val language: String, val criticalTerms: List<String>)

    /** Minimal CSV reader (quoted fields, commas inside quotes). Header: audio,reference,language,critical_terms */
    fun parseManifest(csv: String): List<Item> {
        val rows = csv.lines().filter { it.isNotBlank() }.map(::splitCsv)
        if (rows.isEmpty()) return emptyList()
        val h = rows[0].map { it.trim().lowercase() }
        fun col(r: List<String>, name: String) = h.indexOf(name).let { if (it in r.indices) r[it] else "" }
        return rows.drop(1).map {
            Item(col(it, "audio"), col(it, "reference"), col(it, "language").ifBlank { "auto" },
                col(it, "critical_terms").split(';').map(String::trim).filter(String::isNotEmpty))
        }
    }

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    fun row(item: Item, hyp: String, latencyS: Double, error: String?): BenchRow {
        val (hit, tot) = Metrics.criticalHits(item.criticalTerms, hyp)
        return BenchRow(item.audio, item.language, item.reference, hyp, Metrics.wer(item.reference, hyp),
            Metrics.cer(item.reference, hyp), hit, tot, Metrics.firstWordLost(item.reference, hyp),
            Metrics.lastWordLost(item.reference, hyp), latencyS, error)
    }

    fun summary(rows: List<BenchRow>): String = buildString {
        fun agg(label: String, rs: List<BenchRow>) {
            if (rs.isEmpty()) return
            val ct = rs.sumOf { it.criticalTotal }
            val lat = rs.map { it.latencyS }.sorted()
            val refWords = rs.sumOf { Metrics.wordCount(it.reference) }
            val wordErrors = rs.sumOf { Metrics.wordErrors(it.reference, it.hypothesis) }
            val refChars = rs.sumOf { Metrics.characterCount(it.reference) }
            val charErrors = rs.sumOf { Metrics.characterErrors(it.reference, it.hypothesis) }
            // Pool edit counts over the complete corpus; retain macro averages as a separate diagnostic.
            val corpusWer = wordErrors.toDouble() / maxOf(refWords, 1)
            val corpusCer = charErrors.toDouble() / maxOf(refChars, 1)
            append("$label  n=${rs.size}  WER(corpus)=${\"%.3f\".format(Locale.US, corpusWer)}  ")
            append("macro-WER=${\"%.3f\".format(Locale.US, rs.map { it.wer }.average())}  ")
            append("CER(corpus)=${\"%.3f\".format(Locale.US, corpusCer)}  ")
            append("macro-CER=${\"%.3f\".format(Locale.US, rs.map { it.cer }.average())}  ")
            append("critical=${if (ct > 0) "%.1f%%".format(Locale.US, 100.0 * rs.sumOf { it.criticalHit } / ct) else "-"}  ")
            append("first-lost=${rs.count { it.firstWordLost }}  last-lost=${rs.count { it.lastWordLost }}  ")
            append("empty=${rs.count { it.hypothesis.isBlank() }}  errors=${rs.count { it.error != null }}  ")
            append("p50=${"%.2f".format(Locale.US, lat[lat.size / 2])}s\n")
        }
        agg("overall", rows)
        rows.map { it.language }.distinct().sorted().forEach { l -> agg(l, rows.filter { it.language == l }) }
    }

    fun toCsv(rows: List<BenchRow>): String = buildString {
        append("audio,language,wer,cer,critical_hit,critical_total,first_word_lost,last_word_lost,latency_s,error,reference,hypothesis\n")
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        rows.forEach {
            append("${q(it.audio)},${it.language},${"%.4f".format(Locale.US, it.wer)},${"%.4f".format(Locale.US, it.cer)},${it.criticalHit},")
            append("${it.criticalTotal},${it.firstWordLost},${it.lastWordLost},${"%.3f".format(Locale.US, it.latencyS)},${q(it.error)},")
            append("${q(it.reference)},${q(it.hypothesis)}\n")
        }
    }
}
