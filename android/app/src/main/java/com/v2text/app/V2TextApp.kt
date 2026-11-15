package com.v2text.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.v2text.app.data.HistoryDb
import com.v2text.app.data.Settings
import com.v2text.app.engine.AndroidRecognizerEngine
import com.v2text.app.engine.ModelManager
import com.v2text.app.engine.SpeechEngine
import com.v2text.app.engine.WhisperEngine
import java.io.File

class V2TextApp : Application() {
    lateinit var settings: Settings; private set
    lateinit var db: HistoryDb; private set
    lateinit var models: ModelManager; private set
    lateinit var controller: DictationController; private set

    val whisper by lazy {
        WhisperEngine({ settings.model?.let { File(models.dir, it) } }, { settings.threads }, { settings.beamSize })
    }
    val android by lazy { AndroidRecognizerEngine(this) }

    /** Test hook: lets unit tests run the real pipeline with a fake engine. */
    var engineOverride: SpeechEngine? = null

    fun engine(): SpeechEngine = engineOverride ?: if (settings.engine == "android") android else whisper

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        db = HistoryDb(this)
        models = ModelManager(this)
        controller = DictationController(this)
        if (settings.model == null) settings.model = models.installed().firstOrNull()?.name
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_BUBBLE, "Dictation bubble", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Shown while the v2Text bubble is active (required for microphone use)" })
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Dictation results", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Failed insertions and retry actions" })
        controller.purgeExpiredAudio()
    }

    companion object {
        const val CH_BUBBLE = "bubble"
        const val CH_RESULT = "result"
    }
}
