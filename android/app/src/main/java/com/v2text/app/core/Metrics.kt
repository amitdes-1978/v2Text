package com.v2text.app.core

import java.text.Normalizer

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

    fun wer(ref: String, hyp: String): Double {
        val r = words(ref)
        return editDistance(r, words(hyp)).toDouble() / maxOf(r.size, 1)
    }

    fun cer(ref: String, hyp: String): Double {
        val r = normalize(ref).replace(" ", "").toList()
        return editDistance(r, normalize(hyp).replace(" ", "").toList()).toDouble() / maxOf(r.size, 1)
    }

    fun criticalHits(terms: List<String>, hyp: String): Pair<Int, Int> {
        val n = normalize(hyp)
        val t = terms.filter { it.isNotBlank() }
        return t.count { n.contains(normalize(it)) } to t.size
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
            append("$label  n=${rs.size}  WER=${"%.3f".format(rs.map { it.wer }.average())}  ")
            append("CER=${"%.3f".format(rs.map { it.cer }.average())}  ")
            append("critical=${if (ct > 0) "%.1f%%".format(100.0 * rs.sumOf { it.criticalHit } / ct) else "-"}  ")
            append("first-lost=${rs.count { it.firstWordLost }}  last-lost=${rs.count { it.lastWordLost }}  ")
            append("empty=${rs.count { it.hypothesis.isBlank() }}  errors=${rs.count { it.error != null }}  ")
            append("p50=${"%.2f".format(lat[lat.size / 2])}s\n")
        }
        agg("overall", rows)
        rows.map { it.language }.distinct().sorted().forEach { l -> agg(l, rows.filter { it.language == l }) }
    }

    fun toCsv(rows: List<BenchRow>): String = buildString {
        append("audio,language,wer,cer,critical_hit,critical_total,first_word_lost,last_word_lost,latency_s,error,reference,hypothesis\n")
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        rows.forEach {
            append("${q(it.audio)},${it.language},${"%.4f".format(it.wer)},${"%.4f".format(it.cer)},${it.criticalHit},")
            append("${it.criticalTotal},${it.firstWordLost},${it.lastWordLost},${"%.3f".format(it.latencyS)},${q(it.error)},")
            append("${q(it.reference)},${q(it.hypothesis)}\n")
        }
    }
}
