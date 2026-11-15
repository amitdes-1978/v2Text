package com.v2text.app.engine

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.v2text.app.core.LanguageProfile
import com.v2text.app.core.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android's system on-device recognizer (SpeechRecognizer.createOnDeviceSpeechRecognizer),
 * fed with OUR recorded audio through EXTRA_AUDIO_SOURCE (API 33+), so the same audio can
 * be kept for retry and benchmarking. Language availability depends on what the phone has
 * installed; check with [checkSupport].
 *
 * Note: if a recognizer implementation ignores EXTRA_AUDIO_SOURCE it would try the mic
 * itself; v2Text reports that as NO_MATCH. Verify on the device with the benchmark screen.
 */
class AndroidRecognizerEngine(private val context: Context) : SpeechEngine {
    override val id = "android"
    override val label = "Android on-device recognizer"

    fun isAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun intent(locale: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    data class Support(val installed: List<String>, val supported: List<String>, val pending: List<String>, val error: Int?)

    suspend fun checkSupport(locale: String): Support = withContext(Dispatchers.Main) {
        if (!isAvailable()) return@withContext Support(emptyList(), emptyList(), emptyList(), -1)
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            withTimeout(10_000) {
                suspendCancellableCoroutine { cont ->
                    r.checkRecognitionSupport(intent(locale), context.mainExecutor, object : RecognitionSupportCallback {
                        override fun onSupportResult(s: RecognitionSupport) {
                            if (cont.isActive) cont.resume(Support(s.installedOnDeviceLanguages,
                                s.supportedOnDeviceLanguages, s.pendingOnDeviceLanguages, null))
                        }
                        override fun onError(error: Int) {
                            if (cont.isActive) cont.resume(Support(emptyList(), emptyList(), emptyList(), error))
                        }
                    })
                }
            }
        } finally { r.destroy() }
    }

    /** Asks the system to download the on-device model for [locale] (shows system UI). */
    fun requestModelDownload(locale: String) {
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try { r.triggerModelDownload(intent(locale)) } finally { r.destroy() }
    }

    override suspend fun transcribe(audio: FloatArray, profile: LanguageProfile, hints: List<String>, @Suppress("UNUSED_PARAMETER") textBefore: String?): Transcript {
        if (!isAvailable()) throw SttException("This phone has no on-device speech recognizer. Use the Whisper engine.",
            SttException.Kind.UNSUPPORTED_DEVICE)
        val pcm = Wav.pcmToBytes(Wav.toPcm(audio))
        val t0 = System.nanoTime()
        val pipe = ParcelFileDescriptor.createPipe()
        // Writer thread: stream PCM into the pipe, then close so the recognizer sees end-of-audio.
        val writer = Thread {
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pcm) } } catch (_: IOException) { }
        }
        return withContext(Dispatchers.Main) {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            try {
                withTimeout(60_000) {
                    suspendCancellableCoroutine<Transcript> { cont ->
                        r.setRecognitionListener(object : RecognitionListener {
                            override fun onResults(results: Bundle) {
                                val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val conf = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                                val text = list?.firstOrNull().orEmpty()
                                if (cont.isActive) cont.resume(Transcript(text, id, "system:${profile.androidLocale}",
                                    profile.androidLocale, (System.nanoTime() - t0) / 1e9,
                                    conf?.firstOrNull()?.takeIf { it >= 0f }))
                            }
                            override fun onError(error: Int) {
                                val kind = when (error) {
                                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttException.Kind.NO_MATCH
                                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                        SttException.Kind.LANGUAGE_UNAVAILABLE
                                    else -> SttException.Kind.FAILED
                                }
                                if (cont.isActive) cont.resumeWithException(
                                    SttException("System recognizer error ${errorName(error)}", kind))
                            }
                            override fun onReadyForSpeech(params: Bundle?) {}
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(rmsdB: Float) {}
                            override fun onBufferReceived(buffer: ByteArray?) {}
                            override fun onEndOfSpeech() {}
                            override fun onPartialResults(partialResults: Bundle?) {}
                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })
                        val i = intent(profile.androidLocale).apply {
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                        }
                        r.startListening(i)
                        writer.start()
                        cont.invokeOnCancellation { r.cancel() }
                    }
                }
            } finally {
                r.destroy()
                try { pipe[0].close() } catch (_: IOException) { }
            }
        }
    }

    private fun errorName(e: Int) = when (e) {
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH (no speech recognised)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE (download it in the app)"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
        else -> e.toString()
    }
}
