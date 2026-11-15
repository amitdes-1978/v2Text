package com.v2text.app.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.v2text.app.V2TextApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Tiny view-building helpers so screens stay readable without extra libraries. */
abstract class BasePage : Activity() {
    protected val app get() = application as V2TextApp
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    protected lateinit var root: LinearLayout

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        val scroll = ScrollView(this).apply { addView(root); isFillViewport = true }
        // targetSdk 35 draws edge-to-edge: keep content clear of the status / navigation bars
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        setContentView(scroll)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    protected fun LinearLayout.h1(t: String) = TextView(context).apply {
        text = t; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, dp(4))
    }.also { addView(it) }

    protected fun LinearLayout.h2(t: String) = TextView(context).apply {
        text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(18), 0, dp(6))
    }.also { addView(it) }

    protected fun LinearLayout.p(t: String, size: Float = 14f, alpha: Float = 1f) = TextView(context).apply {
        text = t; textSize = size; this.alpha = alpha; setPadding(0, dp(2), 0, dp(2)); setTextIsSelectable(true)
    }.also { addView(it) }

    protected fun LinearLayout.button(t: String, onClick: () -> Unit) = Button(context).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }.also { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

    // baselineAligned=false: otherwise a multi-line label is pushed down and clipped next to a button
    protected fun LinearLayout.row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        .also { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

    protected fun LinearLayout.smallButton(t: String, onClick: () -> Unit) = Button(context).apply {
        text = t; isAllCaps = false; textSize = 13f; setOnClickListener { onClick() }
    }.also { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }

    protected fun LinearLayout.card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x1A808080) }
    }.also { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }) }

    protected fun LinearLayout.toggle(t: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(context).apply {
        text = t; isChecked = checked; textSize = 15f; setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }.also { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

    protected fun <T> LinearLayout.choice(label: String, options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit): Spinner {
        p(label, 13f, 0.75f)
        val sp = Spinner(context)
        sp.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, options.map { it.second }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sp.setSelection(options.indexOfFirst { it.first == current }.coerceAtLeast(0))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onPick(options[pos].first) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        addView(sp, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return sp
    }

    protected fun LinearLayout.input(hint: String, value: String = "", multiLine: Boolean = false) = EditText(context).apply {
        this.hint = hint; setText(value)
        inputType = if (multiLine) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else InputType.TYPE_CLASS_TEXT
        if (multiLine) minLines = 4
    }.also { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

    protected fun alert(title: String, msg: String, ok: String = "OK", cancel: String? = "Cancel", onOk: () -> Unit = {}) {
        android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .apply { cancel?.let { setNegativeButton(it, null) } }.show()
    }
}
