package com.v2text.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import com.v2text.app.DictationController
import com.v2text.app.R
import com.v2text.app.V2TextApp
import com.v2text.app.audio.Recorder
import com.v2text.app.ui.HistoryActivity
import com.v2text.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Foreground service (type = microphone) that shows the floating dictation bubble over
 * other apps, next to the user's normal keyboard.
 *
 * Bubble gestures:
 *   - tap            -> start recording; tap again to stop (hands-free)
 *   - press and hold -> push-to-talk; release to stop
 *   - drag           -> move the bubble (cancels a take started by the touch)
 *
 * It must be started from the visible app (MainActivity) so Android 14 grants
 * while-in-use microphone access to the service.
 */
class BubbleService : Service() {
    private val app get() = application as V2TextApp
    private val controller: DictationController get() = app.controller
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var bubble: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var recorder: Recorder? = null

    private enum class Mode { IDLE, HOLD, TOGGLE, PROCESSING }
    private var mode = Mode.IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> show()
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_CANCEL -> cancelTake()
            ACTION_INSERT, ACTION_INSERT_AGAIN -> intent.getLongExtra(EXTRA_ID, -1).let { id ->
                scope.launch {
                    V2TextAccessibilityService.instance?.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    delay(700) // let the shade close so the original text field has focus again
                    val r = controller.insertSession(id, force = intent.action == ACTION_INSERT_AGAIN)
                    toast(r.message)
                    if (r == InsertResult.OK) nm().cancel(id.toInt())
                }
            }
            ACTION_COPY -> intent.getLongExtra(EXTRA_ID, -1).let { id ->
                app.db.get(id)?.clean?.let { controller.copyToClipboard(it); toast("Copied") }
                nm().cancel(id.toInt())
            }
            ACTION_RETRY -> intent.getLongExtra(EXTRA_ID, -1).let { id ->
                nm().cancel(id.toInt())
                bubble?.state = BubbleView.State.PROCESSING
                scope.launch {
                    val out = withContext(Dispatchers.Default) { controller.retry(id) }
                    report(out)
                }
            }
        }
        if (bubble == null && intent?.action != ACTION_SHOW) stopSelfIfIdle()
        return START_NOT_STICKY
    }

    private fun stopSelfIfIdle() = main.postDelayed({ if (bubble == null && mode == Mode.IDLE) stopSelf() }, 15_000)

    private fun foregroundNotification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, V2TextApp.CH_BUBBLE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("v2Text bubble is on")
            .setContentText("The microphone is used only while the bubble is red.")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !android.provider.Settings.canDrawOverlays(this)) {
            toast("v2Text needs microphone and 'display over other apps' permission"); stopSelf(); return
        }
        try {
            startForeground(NOTIF_FG, foregroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {   // e.g. Android refusing a microphone service started from the background
            toast("Open v2Text and start the bubble from there (${e.javaClass.simpleName})"); stopSelf(); return
        }
        if (bubble != null) return
        wm = getSystemService(WindowManager::class.java)
        val s = app.settings
        recorder = Recorder(if (s.preroll) s.prerollMs else 0, s.tailMs, warm = s.preroll).also { rec ->
            rec.onLive = { main.post { if (mode == Mode.HOLD || mode == Mode.TOGGLE) bubble?.state = BubbleView.State.RECORDING } }
            try { rec.prepare() } catch (e: Exception) { toast(e.message ?: "Microphone unavailable") }
        }
        val size = (64 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences("bubble", Context.MODE_PRIVATE)
        params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", resources.displayMetrics.widthPixels - size - 16)
            y = prefs.getInt("y", resources.displayMetrics.heightPixels / 3)
        }
        val v = BubbleView(this)
        v.levelProvider = { recorder?.level ?: 0f }
        v.setOnTouchListener(TouchHandler())
        v.onAccessibilityClick = {
            when (mode) {
                Mode.IDLE -> { startTake(); if (mode == Mode.HOLD) { mode = Mode.TOGGLE; v.handsFree = true } }
                Mode.TOGGLE, Mode.HOLD -> finishTake()
                Mode.PROCESSING -> toast("Still processing the last dictation…")
            }
        }
        wm.addView(v, params)
        bubble = v
        // Load the speech model now so the first dictation isn't slow.
        scope.launch(Dispatchers.Default) {
            try { app.engine().warmUp() } catch (e: Exception) { main.post { toast(e.message ?: "Speech engine not ready") } }
        }
    }

    // Touch gestures are handled here; accessibility clicks go through BubbleView.performClick().
    @SuppressLint("ClickableViewAccessibility")
    private inner class TouchHandler : android.view.View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var downAt = 0L; private var dragging = false; private var startedThisTouch = false

        override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
            val p = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
                    downAt = SystemClock.elapsedRealtime(); dragging = false; startedThisTouch = false
                    if (mode == Mode.IDLE) { startTake(); startedThisTouch = mode == Mode.HOLD }   // start at once: no lost first word
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop * 2 || abs(dy) > slop * 2)) {
                        dragging = true
                        if (startedThisTouch) cancelTake()
                    }
                    if (dragging) { p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); wm.updateViewLayout(v, p) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        getSharedPreferences("bubble", Context.MODE_PRIVATE).edit().putInt("x", p.x).putInt("y", p.y).apply()
                        return true
                    }
                    val held = SystemClock.elapsedRealtime() - downAt
                    when {
                        mode == Mode.PROCESSING -> toast("Still processing the last dictation…")
                        startedThisTouch && held >= HOLD_MS -> finishTake()                      // push-to-talk
                        startedThisTouch -> { mode = Mode.TOGGLE; bubble?.handsFree = true }      // tap: keep going
                        mode == Mode.TOGGLE -> finishTake()                                       // second tap: stop
                    }
                }
            }
            return true
        }
    }

    private fun startTake() {
        val rec = recorder ?: return
        app.engineProblem()?.let { problem ->
            // Don't let the user speak into a pipeline that can't transcribe.
            flash(BubbleView.State.ERROR); toast(problem)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        mode = Mode.HOLD
        bubble?.handsFree = false
        bubble?.state = BubbleView.State.STARTING   // switches to RECORDING when real audio arrives
        try { rec.start() } catch (e: Exception) {
            mode = Mode.IDLE; flash(BubbleView.State.ERROR); toast(e.message ?: "Microphone unavailable")
        }
    }

    private fun cancelTake() {
        if (mode == Mode.HOLD || mode == Mode.TOGGLE) {
            recorder?.cancel(); mode = Mode.IDLE; bubble?.state = BubbleView.State.IDLE
        }
    }

    private fun finishTake() {
        val rec = recorder ?: return
        mode = Mode.PROCESSING
        bubble?.state = BubbleView.State.PROCESSING
        scope.launch {
            val out = withContext(Dispatchers.Default) {
                val (pcm, timing) = rec.stop()
                controller.process(pcm, timing, rec.route)
            }
            report(out)
        }
    }

    private fun report(out: DictationController.Outcome) {
        mode = Mode.IDLE
        when (out.status) {
            "ok" -> flash(BubbleView.State.DONE)
            "preview" -> { flash(BubbleView.State.DONE); resultNotification(out, "Tap Insert to type it", insert = true) }
            "too_short" -> { flash(BubbleView.State.ERROR); toast(out.error ?: "Too short") }
            "insert_failed" -> { flash(BubbleView.State.ERROR); resultNotification(out, out.error ?: "", insert = true) }
            else -> {
                flash(BubbleView.State.ERROR)
                toast(out.error ?: out.status)
                if (out.canRetry) resultNotification(out, (out.error ?: "Recognition failed") +
                    if (out.error?.contains("model", ignoreCase = true) == true)
                        "\nYour recording is saved: download a model in v2Text, then tap Retry." else "",
                    insert = false, retry = true)
            }
        }
        out.report?.warnings?.firstOrNull()?.let { if (out.status == "ok") toast("⚠ $it") }
    }

    private fun resultNotification(out: DictationController.Outcome, msg: String, insert: Boolean, retry: Boolean = false) {
        if (out.id < 0) return
        fun pi(action: String, code: Int) = PendingIntent.getService(this, out.id.toInt() * 10 + code,
            Intent(this, BubbleService::class.java).setAction(action).putExtra(EXTRA_ID, out.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val history = PendingIntent.getActivity(this, 3, Intent(this, HistoryActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, V2TextApp.CH_RESULT)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (out.clean.isNotEmpty()) out.clean.take(80) else "Dictation #${out.id}")
            .setContentText(msg).setStyle(Notification.BigTextStyle().bigText(
                (if (out.clean.isNotEmpty()) out.clean + "\n\n" else "") + msg))
            .setContentIntent(history).setAutoCancel(true)
        if (insert) {
            b.addAction(Notification.Action.Builder(null, "Insert", pi(ACTION_INSERT, 1)).build())
            b.addAction(Notification.Action.Builder(null, "Copy", pi(ACTION_COPY, 2)).build())
        }
        if (retry) b.addAction(Notification.Action.Builder(null, "Retry transcription", pi(ACTION_RETRY, 3)).build())
        nm().notify(out.id.toInt(), b.build())
    }

    private fun flash(s: BubbleView.State) {
        bubble?.state = s
        main.postDelayed({ if (mode == Mode.IDLE) bubble?.state = BubbleView.State.IDLE }, 900)
    }

    private fun nm() = getSystemService(NotificationManager::class.java)
    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        recorder?.close(); recorder = null
        bubble?.let { wm.removeView(it) }; bubble = null
        scope.cancel()
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOW = "com.v2text.SHOW"
        const val ACTION_STOP = "com.v2text.STOP"
        const val ACTION_CANCEL = "com.v2text.CANCEL"
        const val ACTION_INSERT = "com.v2text.INSERT"
        const val ACTION_INSERT_AGAIN = "com.v2text.INSERT_AGAIN"
        const val ACTION_COPY = "com.v2text.COPY"
        const val ACTION_RETRY = "com.v2text.RETRY"
        const val EXTRA_ID = "id"
        private const val NOTIF_FG = 1
        private const val HOLD_MS = 450L

        @Volatile var running = false

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, BubbleService::class.java).setAction(ACTION_SHOW))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, BubbleService::class.java).setAction(ACTION_STOP))
    }

    override fun onCreate() { super.onCreate(); running = true }
}
