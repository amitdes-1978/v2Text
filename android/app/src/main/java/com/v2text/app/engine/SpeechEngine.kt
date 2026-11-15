package com.v2text.app.engine

import com.v2text.app.core.LanguageProfile

/** Verbatim engine output. Never edited after it is produced. */
data class Transcript(
    val text: String,
    val engine: String,
    val model: String,
    val language: String?,      // what the engine reports, if anything
    val seconds: Double,        // processing time
    val confidence: Float? = null, // only when the engine genuinely supplies one
)

class SttException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind { MODEL_MISSING, UNSUPPORTED_DEVICE, LANGUAGE_UNAVAILABLE, NO_MATCH, FAILED }
}

/** Provider-independent interface (blueprint Phase 4). Every engine runs fully on-device. */
interface SpeechEngine {
    val id: String
    val label: String

    /** [audio] is mono float PCM at 16 kHz. */
    suspend fun transcribe(audio: FloatArray, profile: LanguageProfile, hints: List<String>): Transcript

    /** Load models ahead of the first dictation (optional). */
    suspend fun warmUp() {}

    fun release() {}
}
