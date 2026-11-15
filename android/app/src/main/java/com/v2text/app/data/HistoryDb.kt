package com.v2text.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local session store. The verbatim transcript column is written once at insert
 * time and never updated (there is no API to change it).
 */
data class SessionRow(
    val id: Long, val ts: Long, val status: String, val error: String?, val pkg: String?, val language: String?,
    val engine: String?, val model: String?, val verbatim: String?, val clean: String?, val changes: String?,
    val audioPath: String?, val audioSeconds: Double, val sttSeconds: Double, val diagnostics: String?,
    val inserted: Boolean, val insertMethod: String?, val words: Int, val retryOf: Long?, val route: String?,
    val startLatencyMs: Long,
)

class HistoryDb(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL,
            status TEXT NOT NULL,      -- ok | preview | no_speech | too_short | stt_error | insert_failed | blocked
            error TEXT, pkg TEXT, language TEXT, engine TEXT, model TEXT,
            verbatim TEXT,             -- immutable engine output
            clean TEXT, changes TEXT,
            audio_path TEXT, audio_seconds REAL, stt_seconds REAL, diagnostics TEXT,
            inserted INTEGER NOT NULL DEFAULT 0, insert_method TEXT,
            words INTEGER NOT NULL DEFAULT 0, retry_of INTEGER, route TEXT, start_latency_ms INTEGER)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun add(v: ContentValues): Long {
        v.put("ts", System.currentTimeMillis())
        return writableDatabase.insert("sessions", null, v)
    }

    /** Status / insertion / audio updates only. Verbatim text cannot be changed. */
    fun update(id: Long, status: String? = null, error: String? = null, inserted: Boolean? = null,
               insertMethod: String? = null, audioPath: String? = null, clearAudio: Boolean = false) {
        val v = ContentValues()
        status?.let { v.put("status", it) }
        error?.let { v.put("error", it) }
        inserted?.let { v.put("inserted", if (it) 1 else 0) }
        insertMethod?.let { v.put("insert_method", it) }
        audioPath?.let { v.put("audio_path", it) }
        if (clearAudio) v.putNull("audio_path")
        if (v.size() > 0) writableDatabase.update("sessions", v, "id=?", arrayOf(id.toString()))
    }

    fun get(id: Long): SessionRow? =
        readableDatabase.rawQuery("SELECT * FROM sessions WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) row(it) else null }

    fun recent(limit: Int = 100): List<SessionRow> =
        readableDatabase.rawQuery("SELECT * FROM sessions ORDER BY id DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }

    fun delete(id: Long) { writableDatabase.delete("sessions", "id=?", arrayOf(id.toString())) }
    fun clear() { writableDatabase.delete("sessions", null, null) }

    fun stats(): String = readableDatabase.rawQuery(
        "SELECT COUNT(*), SUM(status='ok'), SUM(status='no_speech'), SUM(status='stt_error'), SUM(status='insert_failed'), " +
            "COALESCE(SUM(words),0), COALESCE(SUM(audio_seconds),0), AVG(CASE WHEN stt_seconds>0 THEN stt_seconds END), " +
            "AVG(CASE WHEN start_latency_ms>=0 THEN start_latency_ms END) FROM sessions", null).use { c ->
        c.moveToFirst()
        val mins = c.getDouble(6) / 60
        "sessions ${c.getInt(0)} · ok ${c.getInt(1)} · no speech ${c.getInt(2)} · recognition errors ${c.getInt(3)} · " +
            "insert failures ${c.getInt(4)}\nwords ${c.getInt(5)} · spoken ${"%.1f".format(mins)} min · " +
            "avg ${if (mins > 0) (c.getInt(5) / mins).toInt() else 0} wpm · avg recognition ${"%.2f".format(c.getDouble(7))} s · " +
            "avg mic start ${c.getDouble(8).toInt()} ms"
    }

    private fun row(c: Cursor): SessionRow {
        fun s(n: String) = c.getColumnIndex(n).let { if (c.isNull(it)) null else c.getString(it) }
        fun d(n: String) = c.getColumnIndex(n).let { if (c.isNull(it)) 0.0 else c.getDouble(it) }
        fun l(n: String) = c.getColumnIndex(n).let { if (c.isNull(it)) null else c.getLong(it) }
        return SessionRow(l("id")!!, l("ts")!!, s("status")!!, s("error"), s("pkg"), s("language"), s("engine"), s("model"),
            s("verbatim"), s("clean"), s("changes"), s("audio_path"), d("audio_seconds"), d("stt_seconds"), s("diagnostics"),
            (l("inserted") ?: 0L) == 1L, s("insert_method"), (l("words") ?: 0L).toInt(), l("retry_of"), s("route"),
            l("start_latency_ms") ?: -1)
    }
}
