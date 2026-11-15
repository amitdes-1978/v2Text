package com.v2text.app.engine

import android.util.Log
import com.v2text.app.core.LanguageProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File

/** JNI entry points implemented in src/main/cpp/v2text_jni.c. */
object WhisperNative {
    private const val TAG = "WhisperNative"

    /** Null when the library can be loaded; otherwise the reason it can't. */
    val unavailableReason: String? by lazy {
        val info = try { File("/proc/cpuinfo").readText() } catch (e: Exception) { "" }
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
        when {
            abi != "arm64-v8a" -> "This build of the Whisper engine needs an arm64 phone (found $abi)."
            info.isNotEmpty() && !(info.contains("fphp") && info.contains("asimddp")) ->
                "This phone's CPU lacks ARMv8.2 fp16/dot-product support required by this Whisper build."
            else -> try {
                System.loadLibrary("v2text_whisper"); null
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "loadLibrary failed", e); "Native Whisper library failed to load: ${e.message}"
            }
        }
    }

    @JvmStatic external fun initContext(path: String): Long
    @JvmStatic external fun freeContext(ptr: Long)
    @JvmStatic external fun transcribe(ptr: Long, threads: Int, audio: FloatArray, language: String?,
                                       prompt: ByteArray?, beamSize: Int): ByteArray?
    @JvmStatic external fun detectedLanguage(ptr: Long): String?
    @JvmStatic external fun systemInfo(): String
}

/**
 * Local Whisper via whisper.cpp. whisper.cpp contexts must not be used from two
 * threads at once, so all calls run on one dedicated thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhisperEngine(private val modelFile: () -> File?, private val threads: () -> Int,
                    private val beamSize: () -> Int) : SpeechEngine {
    override val id = "whisper"
    override val label = "Whisper (whisper.cpp, on-device)"

    private val thread = Dispatchers.IO.limitedParallelism(1)
    private var ptr = 0L
    private var loadedPath: String? = null

    private fun ensureLoaded(): String {
        WhisperNative.unavailableReason?.let { throw SttException(it, SttException.Kind.UNSUPPORTED_DEVICE) }
        val f = modelFile() ?: throw SttException("No Whisper model installed. Download or import one first.",
            SttException.Kind.MODEL_MISSING)
        if (!f.exists()) throw SttException("Model file missing: ${f.name}", SttException.Kind.MODEL_MISSING)
        if (ptr != 0L && loadedPath == f.absolutePath) return f.name
        if (ptr != 0L) { WhisperNative.freeContext(ptr); ptr = 0L }
        val p = WhisperNative.initContext(f.absolutePath)
        if (p == 0L) throw SttException("Model ${f.name} could not be loaded (corrupt or not a ggml Whisper model). " +
            "Delete it and download again.", SttException.Kind.MODEL_MISSING)
        ptr = p
        loadedPath = f.absolutePath
        return f.name
    }

    override suspend fun warmUp() { withContext(thread) { ensureLoaded() } }

    override suspend fun transcribe(audio: FloatArray, profile: LanguageProfile, hints: List<String>): Transcript =
        withContext(thread) {
            val model = ensureLoaded()
            val prompt = listOf(profile.prompt, if (hints.isEmpty()) "" else "Vocabulary: ${hints.joinToString(", ")}.")
                .filter { it.isNotBlank() }.joinToString(" ")
            val t0 = System.nanoTime()
            val bytes = WhisperNative.transcribe(ptr, threads(), audio, profile.whisperLanguage,
                prompt.ifBlank { null }?.toByteArray(Charsets.UTF_8), beamSize())
                ?: throw SttException("Whisper failed to decode this recording.", SttException.Kind.FAILED)
            val secs = (System.nanoTime() - t0) / 1e9
            Transcript(String(bytes, Charsets.UTF_8).trim(), id, model, WhisperNative.detectedLanguage(ptr), secs)
        }

    override fun release() {
        if (ptr != 0L) { WhisperNative.freeContext(ptr); ptr = 0L; loadedPath = null }
    }
}
