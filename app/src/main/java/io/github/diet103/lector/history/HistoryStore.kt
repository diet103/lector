package io.github.diet103.lector.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything Lector has read, on disk (PLAN §6, v0.2).
 *
 * **Deliberately not Room.** One table, no joins, no relations. Room would add KSP — which only
 * gained AGP 9 built-in-Kotlin support in 2.3.1, on a toolchain that already cost this project a
 * day — plus generated code and reflective lookup, which is exactly the shape of thing R8 silently
 * broke in P7 while every unit test stayed green. `SQLiteOpenHelper` is in the platform, has no
 * annotation processor and nothing for R8 to strip. Revisit if this ever grows past two tables.
 *
 * Search is `LIKE`, not FTS: a few hundred rows scan instantly, and FTS5 availability across
 * API 26+ isn't guaranteed.
 *
 * **This file reverses PLAN §4's "selected text is never persisted"**, so the constraints around it
 * are the feature, not decoration: the database is excluded from backup and device transfer (see
 * `res/xml/backup_rules.xml`), it is cleared on sign-out, it can be emptied in one tap, and it
 * stores recognized text only — never a screenshot.
 */
class HistoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    /**
     * Bumped on every write so the UI can re-query. The rows themselves aren't held here: a
     * history can be long, and Compose only ever shows a page of it.
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_KEY TEXT PRIMARY KEY NOT NULL,
                $COL_TEXT TEXT NOT NULL,
                $COL_TITLE TEXT,
                $COL_SOURCE TEXT NOT NULL,
                $COL_SOURCE_URL TEXT,
                $COL_VOICE_ID TEXT NOT NULL,
                $COL_MODEL_ID TEXT NOT NULL,
                $COL_FORMAT TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_LAST_READ_AT INTEGER NOT NULL,
                $COL_DURATION_MS INTEGER,
                $COL_AUDIO_BYTES INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX ${TABLE}_last_read ON $TABLE ($COL_LAST_READ_AT DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Records a read, or refreshes one already here. Re-reading the same text with the same voice
     * produces the same [HistoryEntry.key], so it moves back to the top instead of duplicating —
     * and its [HistoryEntry.createdAt] survives, which is what makes "first read three weeks ago"
     * truthful.
     */
    fun record(entry: HistoryEntry) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existingCreatedAt = db.query(
                TABLE,
                arrayOf(COL_CREATED_AT),
                "$COL_KEY = ?",
                arrayOf(entry.key),
                null,
                null,
                null
            ).use { if (it.moveToFirst()) it.getLong(0) else null }

            val values = ContentValues().apply {
                put(COL_KEY, entry.key)
                put(COL_TEXT, entry.text)
                put(COL_TITLE, entry.title)
                put(COL_SOURCE, entry.source.name)
                put(COL_SOURCE_URL, entry.sourceUrl)
                put(COL_VOICE_ID, entry.voiceId)
                put(COL_MODEL_ID, entry.modelId)
                put(COL_FORMAT, entry.outputFormat)
                put(COL_CREATED_AT, existingCreatedAt ?: entry.createdAt)
                put(COL_LAST_READ_AT, entry.lastReadAt)
                put(COL_DURATION_MS, entry.durationMs)
                put(COL_AUDIO_BYTES, entry.audioBytes)
            }
            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            trim(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _revision.value++
    }

    /**
     * Moves an existing read back to the top of the list. Used for replays, which arrive without
     * any record of where the text originally came from — calling [record] for one would relabel a
     * screenshot read as shared text and lose its title.
     *
     * @return false when there is no such row, so the caller can record it properly instead.
     */
    fun touch(key: String, lastReadAt: Long): Boolean {
        val values = ContentValues().apply { put(COL_LAST_READ_AT, lastReadAt) }
        val updated = writableDatabase.update(TABLE, values, "$COL_KEY = ?", arrayOf(key))
        if (updated > 0) _revision.value++
        return updated > 0
    }

    /**
     * Records how long a read's audio runs, as soon as the player knows — which is well before the
     * read finishes, and is what the reader needs to map words onto the timeline. Waiting for the
     * end would leave every unfinished read permanently un-highlightable.
     */
    fun markDuration(key: String, durationMs: Long) {
        val values = ContentValues().apply { put(COL_DURATION_MS, durationMs) }
        val updated = writableDatabase.update(TABLE, values, "$COL_KEY = ?", arrayOf(key))
        if (updated > 0) _revision.value++
    }

    /**
     * Fills in what only the end of playback knows. Kept separate from [record] because a read is
     * written the moment it starts making sound, long before these are known — and a read that is
     * abandoned halfway should still appear, just without a duration.
     */
    fun markCompleted(key: String, durationMs: Long, audioBytes: Long) {
        val values = ContentValues().apply {
            put(COL_DURATION_MS, durationMs)
            put(COL_AUDIO_BYTES, audioBytes)
        }
        writableDatabase.update(TABLE, values, "$COL_KEY = ?", arrayOf(key))
        _revision.value++
    }

    /** Most recently read first. */
    fun recent(limit: Int = DEFAULT_PAGE): List<HistoryEntry> = query(null, limit)

    /** The single most recent read, for Home's "last read" card. */
    fun mostRecent(): HistoryEntry? = recent(1).firstOrNull()

    /**
     * Case-insensitive substring match over the text and the title. A blank query is the whole
     * list rather than nothing — a search box that empties to "no results" reads as broken.
     */
    fun search(query: String, limit: Int = DEFAULT_PAGE): List<HistoryEntry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return recent(limit)
        return query(trimmed, limit)
    }

    fun byKey(key: String): HistoryEntry? =
        readableDatabase.query(TABLE, null, "$COL_KEY = ?", arrayOf(key), null, null, null)
            .use { if (it.moveToFirst()) it.toEntry() else null }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun delete(key: String) {
        writableDatabase.delete(TABLE, "$COL_KEY = ?", arrayOf(key))
        _revision.value++
    }

    fun clearAll() {
        writableDatabase.delete(TABLE, null, null)
        _revision.value++
    }

    private fun query(match: String?, limit: Int): List<HistoryEntry> {
        // ESCAPE, because the query comes from a text field: `%` and `_` are LIKE wildcards, so
        // without this, typing a single `%` matches the entire history.
        val selection = match?.let {
            "$COL_TEXT LIKE ? ESCAPE '$LIKE_ESCAPE' OR $COL_TITLE LIKE ? ESCAPE '$LIKE_ESCAPE'"
        }
        val args = match?.let { arrayOf("%${escapeLike(it)}%", "%${escapeLike(it)}%") }
        return readableDatabase.query(
            TABLE,
            null,
            selection,
            args,
            null,
            null,
            "$COL_LAST_READ_AT DESC",
            limit.toString()
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toEntry()) }
        }
    }

    /** Makes a user-typed string match literally under LIKE. The escape char goes first. */
    private fun escapeLike(raw: String): String = raw
        .replace(LIKE_ESCAPE, "$LIKE_ESCAPE$LIKE_ESCAPE")
        .replace("%", "$LIKE_ESCAPE%")
        .replace("_", "${LIKE_ESCAPE}_")

    /**
     * Keeps the table bounded. Text is small — [MAX_ENTRIES] full-length reads is a couple of
     * megabytes — but an unbounded log of everything someone has ever read is its own privacy
     * problem, quite apart from the disk.
     */
    private fun trim(db: SQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM $TABLE WHERE $COL_KEY NOT IN (
                SELECT $COL_KEY FROM $TABLE ORDER BY $COL_LAST_READ_AT DESC LIMIT $MAX_ENTRIES
            )
            """.trimIndent()
        )
    }

    private fun Cursor.toEntry(): HistoryEntry = HistoryEntry(
        key = getString(getColumnIndexOrThrow(COL_KEY)),
        text = getString(getColumnIndexOrThrow(COL_TEXT)),
        title = getStringOrNull(COL_TITLE),
        source = ReadSource.fromName(getString(getColumnIndexOrThrow(COL_SOURCE))),
        sourceUrl = getStringOrNull(COL_SOURCE_URL),
        voiceId = getString(getColumnIndexOrThrow(COL_VOICE_ID)),
        modelId = getString(getColumnIndexOrThrow(COL_MODEL_ID)),
        outputFormat = getString(getColumnIndexOrThrow(COL_FORMAT)),
        createdAt = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
        lastReadAt = getLong(getColumnIndexOrThrow(COL_LAST_READ_AT)),
        durationMs = getLongOrNull(COL_DURATION_MS),
        audioBytes = getLongOrNull(COL_AUDIO_BYTES)
    )

    private fun Cursor.getStringOrNull(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.getLongOrNull(column: String): Long? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }

    companion object {
        const val DATABASE_NAME = "lector_history.db"
        private const val DATABASE_VERSION = 1

        /** Bounded on purpose — see [trim]. */
        const val MAX_ENTRIES = 500
        private const val DEFAULT_PAGE = 200

        /** A single backslash — both in the LIKE pattern and in the SQL `ESCAPE` clause. */
        private const val LIKE_ESCAPE = "\\"

        private const val TABLE = "reads"
        private const val COL_KEY = "key"
        private const val COL_TEXT = "text"
        private const val COL_TITLE = "title"
        private const val COL_SOURCE = "source"
        private const val COL_SOURCE_URL = "source_url"
        private const val COL_VOICE_ID = "voice_id"
        private const val COL_MODEL_ID = "model_id"
        private const val COL_FORMAT = "output_format"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_LAST_READ_AT = "last_read_at"
        private const val COL_DURATION_MS = "duration_ms"
        private const val COL_AUDIO_BYTES = "audio_bytes"
    }
}
