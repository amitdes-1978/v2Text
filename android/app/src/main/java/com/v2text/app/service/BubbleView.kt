package com.v2text.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** The round floating button. Colour = state; ring = live microphone level. */
class BubbleView(context: Context) : View(context) {
    enum class State(val color: Int, val label: String) {
        IDLE(0xFF3D5AFE.toInt(), "Tap or hold to dictate"),
        STARTING(0xFFFFA000.toInt(), "Starting mic…"),
        RECORDING(0xFFE53935.toInt(), "Recording"),
        PROCESSING(0xFF7E57C2.toInt(), "Processing"),
        DONE(0xFF43A047.toInt(), "Done"),
        ERROR(0xFFFF6D00.toInt(), "Problem"),
    }

    var state = State.IDLE
        set(v) { field = v; contentDescription = "v2Text: ${v.label}"; invalidate() }
    var levelProvider: () -> Float = { 0f }
    var handsFree = false
    /** TalkBack / Switch Access "click" (double-tap) toggles recording. */
    var onAccessibilityClick: (() -> Unit)? = null

    override fun performClick(): Boolean { super.performClick(); onAccessibilityClick?.invoke(); return true }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private var spin = 0f

    init { contentDescription = "v2Text: ${state.label}" }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2; val rad = min(w, h) / 2 * 0.82f
        fill.color = state.color
        fill.setShadowLayer(rad * 0.15f, 0f, rad * 0.06f, 0x66000000)
        c.drawCircle(cx, cy, rad, fill)

        // microphone glyph
        val u = rad / 6
        glyphStroke.strokeWidth = u * 0.55f
        r.set(cx - u * 1.1f, cy - u * 2.6f, cx + u * 1.1f, cy + u * 0.9f)
        c.drawRoundRect(r, u * 1.1f, u * 1.1f, glyph)
        r.set(cx - u * 2f, cy - u * 1.6f, cx + u * 2f, cy + u * 1.9f)
        c.drawArc(r, 20f, 140f, false, glyphStroke)
        c.drawLine(cx, cy + u * 1.9f, cx, cy + u * 2.8f, glyphStroke)

        when (state) {
            State.RECORDING -> {
                val lvl = (levelProvider() * 8f).coerceIn(0f, 1f)
                ring.strokeWidth = rad * 0.10f
                ring.alpha = 230
                c.drawCircle(cx, cy, rad * (0.9f + 0.09f * lvl), ring)
                if (handsFree) { ring.strokeWidth = rad * 0.05f; c.drawCircle(cx, cy, rad * 0.6f, ring) }
                postInvalidateOnAnimation()
            }
            State.PROCESSING, State.STARTING -> {
                ring.strokeWidth = rad * 0.08f
                r.set(cx - rad * 0.92f, cy - rad * 0.92f, cx + rad * 0.92f, cy + rad * 0.92f)
                spin = (spin + 8f) % 360f
                c.drawArc(r, spin, 90f, false, ring)
                postInvalidateOnAnimation()
            }
            else -> {}
        }
    }
}
