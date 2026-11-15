package com.v2text.app.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Whisper ggml model catalog, download (explicit, user-initiated) and import.
 * Names, URLs and SHA-1 hashes are taken from whisper.cpp's models/README.md and
 * models/download-ggml-model.sh. Quantized models have no published hash there,
 * so they are verified by loading instead.
 */
class ModelManager(context: Context) {
    data class ModelInfo(val name: String, val note: String, val sha1: String?) {
        val fileName get() = "ggml-$name.bin"
        val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
    }

    val catalog = listOf(
        ModelInfo("tiny", "75 MiB · fastest, lowest accuracy", "bd577a113a864445d4c299885e0cb97d4ba92b5f"),
        ModelInfo("base", "142 MiB · good start for the A52s", "465707469ff3a37a2b9b8d8f89f2f99de7299dac"),
        ModelInfo("base-q5_1", "quantized base · smaller/faster", null),
        ModelInfo("small", "466 MiB · better Hindi/Gujarati, slower", "55356645c2b361a969dfd0ef2c5a50d530afd8d5"),
        ModelInfo("small-q5_1", "quantized small · smaller/faster", null),
    )

    val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val resolver = context.contentResolver

    fun installed(): List<File> = dir.listFiles { f -> f.name.endsWith(".bin") }?.sortedBy { it.name } ?: emptyList()

    fun file(name: String): File? = File(dir, name).takeIf { it.exists() }

    /** HEAD request so the user sees the real download size before agreeing. */
    fun remoteSize(m: ModelInfo): Long {
        val c = URL(m.url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.instanceFollowRedirects = true; c.connectTimeout = 15000; c.readTimeout = 15000
        return try { c.connect(); c.contentLengthLong } finally { c.disconnect() }
    }

    fun download(m: ModelInfo, onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean): File {
        val part = File(dir, m.fileName + ".part")
        val c = URL(m.url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = 20000; c.readTimeout = 30000
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode} for ${m.fileName}")
            val total = c.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-1")
            c.inputStream.use { inp -> part.outputStream().use { out -> copy(inp, out::write, digest, total, onProgress, cancelled) } }
            if (cancelled()) { part.delete(); throw InterruptedException("download cancelled") }
            m.sha1?.let { expected ->
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (got != expected) { part.delete(); throw IllegalStateException("Checksum mismatch for ${m.fileName}: file is corrupt. Try again.") }
            }
            checkMagic(part)
            val dest = File(dir, m.fileName)
            if (!part.renameTo(dest)) throw IllegalStateException("could not save ${dest.name}")
            return dest
        } finally { c.disconnect(); if (part.exists() && cancelled()) part.delete() }
    }

    /** Import a ggml model the user already has (e.g. copied from a PC) via the system file picker. */
    fun import(uri: Uri, displayName: String?): File {
        val name = (displayName ?: "imported.bin").let { if (it.endsWith(".bin")) it else "$it.bin" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val part = File(dir, "$name.part")
        resolver.openInputStream(uri)?.use { inp -> part.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            ?: throw IllegalStateException("cannot open selected file")
        try { checkMagic(part) } catch (e: Exception) { part.delete(); throw e }
        val dest = File(dir, name)
        part.renameTo(dest)
        return dest
    }

    fun delete(f: File) = f.delete()

    private fun copy(inp: InputStream, write: (ByteArray, Int, Int) -> Unit, md: MessageDigest, total: Long,
                     onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        val buf = ByteArray(1 shl 16)
        var done = 0L; var last = 0L
        while (!cancelled()) {
            val n = inp.read(buf)
            if (n < 0) break
            write(buf, 0, n); md.update(buf, 0, n); done += n
            if (done - last > 1 shl 20) { onProgress(done, total); last = done }
        }
        onProgress(done, total)
    }

    /** ggml Whisper files start with the magic 0x67676d6c ("lmgg" little-endian). */
    private fun checkMagic(f: File) {
        val b = ByteArray(4)
        f.inputStream().use { if (it.read(b) != 4) throw IllegalStateException("file too small") }
        val magic = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
            ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
        if (magic != 0x67676d6c) {
            f.delete()
            throw IllegalStateException("Not a whisper.cpp ggml model (bad header). Use a ggml-*.bin file.")
        }
    }
}
