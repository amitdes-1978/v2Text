package com.v2text.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

enum class InsertResult(val message: String) {
    OK("Inserted"),
    NO_FIELD("No text field is focused. Tap into a text box first; the text is on your clipboard."),
    PASSWORD("v2Text never types into password fields."),
    BLOCKED("Dictation is disabled for this app."),
    SERVICE_OFF("Accessibility access is off, so v2Text can't type for you. The text is on your clipboard."),
    FAILED("This app didn't accept the text. It's on your clipboard: long-press the field and choose Paste."),
    ALREADY_INSERTED("Already inserted. Use 'Insert again' to insert a second copy."),
}

/**
 * Narrow-scope accessibility service. It is used ONLY to put dictated text into the
 * input field that has focus when the user finishes dictating. It does not listen to
 * events, log, or read any screen content other than the focused field's own text and
 * cursor (needed to insert at the cursor). Password fields are never touched.
 */
class V2TextAccessibilityService : AccessibilityService() {
    var lastMethod: String? = null; private set

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}   // events are not used
    override fun onInterrupt() {}

    private fun focused(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    fun focusedPackage(): String? = focused()?.packageName?.toString()

    /**
     * Up to [max] characters before the cursor in the focused field, only for context continuation
     * (opt-in). Never for password fields or blocked apps; never stored or sent anywhere.
     */
    fun contextBeforeCursor(blocked: List<String>, max: Int = 200): String? {
        val node = focused() ?: return null
        if (node.isPassword || node.packageName?.toString() in blocked || node.isShowingHintText) return null
        val text = node.text?.toString() ?: return null
        val cur = node.textSelectionStart.let { if (it in 0..text.length) it else text.length }
        return text.substring(0, cur).takeLast(max)
    }

    fun insert(text: String, blocked: List<String>): InsertResult {
        val node = focused() ?: return InsertResult.NO_FIELD
        if (node.isPassword) return InsertResult.PASSWORD
        if (node.packageName?.toString() in blocked) return InsertResult.BLOCKED

        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var s = node.textSelectionStart; var e = node.textSelectionEnd
        if (s < 0 || e < 0 || s > current.length || e > current.length) { s = current.length; e = current.length }
        val start = minOf(s, e); val end = maxOf(s, e)
        val before = current.substring(0, start); val after = current.substring(end)
        val sep = if (before.isNotEmpty() && !before.last().isWhitespace() && text.firstOrNull()?.let { it.isLetterOrDigit() } == true) " " else ""
        val insertText = sep + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + insertText + after)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val cursor = before.length + insertText.length
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            })
            lastMethod = "set_text"
            return InsertResult.OK
        }
        // Fallback: clipboard + paste action (works in many WebView / custom editors)
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("v2Text", insertText))
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) { lastMethod = "paste"; return InsertResult.OK }
        return InsertResult.FAILED
    }

    companion object {
        @Volatile var instance: V2TextAccessibilityService? = null; private set
    }
}
