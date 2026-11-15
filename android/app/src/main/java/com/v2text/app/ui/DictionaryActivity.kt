package com.v2text.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import com.v2text.app.core.DictEntry

/** Personal dictionary (term + aliases + category) and voice snippets: add, edit, delete, search. */
class DictionaryActivity : BasePage() {
    private lateinit var list: LinearLayout
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root.h1("Dictionary & snippets")
        root.p("Terms are spelled exactly as you enter them and are given to the recognizer as hints. Aliases are exact " +
            "phrases that get replaced with the term (no fuzzy matching; numbers and very short words are never " +
            "replaced).", 12f, 0.75f)
        root.input("Search…").addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s.toString().trim().lowercase(); render() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val r = root.row()
        r.smallButton("+ Term") { editTerm(null) }
        r.smallButton("+ Snippet") { editSnippet(null) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        render()
    }

    private fun render() {
        list.removeAllViews()
        list.h2("Terms")
        app.settings.dictionary.filter { e ->
            query.isEmpty() || e.term.lowercase().contains(query) || e.aliases.any { it.lowercase().contains(query) }
        }.forEach { e ->
            val c = list.card()
            c.p(e.term, 16f)
            c.p("[${e.category}]  ${if (e.aliases.isEmpty()) "no aliases" else "heard as: " + e.aliases.joinToString(", ")}", 12f, 0.7f)
            val r = c.row()
            r.smallButton("Edit") { editTerm(e) }
            r.smallButton("Delete") { app.settings.dictionary = app.settings.dictionary - e; render() }
        }
        list.h2("Snippets")
        list.p("Say the trigger phrase while dictating and the saved text is inserted exactly.", 12f, 0.7f)
        app.settings.snippets.filter { (k, v) -> query.isEmpty() || k.lowercase().contains(query) || v.lowercase().contains(query) }
            .forEach { (k, v) ->
                val c = list.card()
                c.p("“$k”", 15f)
                c.p(v, 13f, 0.8f)
                val r = c.row()
                r.smallButton("Edit") { editSnippet(k) }
                r.smallButton("Delete") { app.settings.snippets = app.settings.snippets - k; render() }
            }
    }

    private fun form(vararg fields: Pair<String, String>): Pair<LinearLayout, List<EditText>> {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val edits = fields.map { (hint, value) -> box.input(hint, value) }
        return box to edits
    }

    private fun editTerm(e: DictEntry?) {
        val (box, f) = form("Term, e.g. LNG" to (e?.term ?: ""), "Aliases, comma-separated, e.g. l n g" to
            (e?.aliases?.joinToString(", ") ?: ""), "Category" to (e?.category ?: "general"))
        AlertDialog.Builder(this).setTitle(if (e == null) "Add term" else "Edit term").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val term = f[0].text.toString().trim()
                if (term.isEmpty()) return@setPositiveButton
                val entry = DictEntry(term, f[1].text.split(',').map(String::trim).filter(String::isNotEmpty),
                    f[2].text.toString().trim().ifEmpty { "general" })
                app.settings.dictionary = app.settings.dictionary.filter { it != e && it.term != term } + entry
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editSnippet(key: String?) {
        val (box, f) = form("Spoken trigger, e.g. my signature" to (key ?: ""),
            "Text to insert" to (key?.let { app.settings.snippets[it] } ?: ""))
        AlertDialog.Builder(this).setTitle(if (key == null) "Add snippet" else "Edit snippet").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val k = f[0].text.toString().trim(); val v = f[1].text.toString()
                if (k.length < 3 || v.isEmpty()) return@setPositiveButton
                app.settings.snippets = (app.settings.snippets - (key ?: "")) + (k to v)
                render()
            }.setNegativeButton("Cancel", null).show()
    }
}
