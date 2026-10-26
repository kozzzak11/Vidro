package app.vidro.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class VidroDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "vidro.db", null, 1) {
    private val lock = Any()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE bookmarks (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL,
              url TEXT NOT NULL UNIQUE,
              created INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE history (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL,
              url TEXT NOT NULL,
              visited INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_history_visited ON history(visited DESC)")
        db.execSQL(
            """
            CREATE TABLE tabs (
              id TEXT PRIMARY KEY,
              title TEXT NOT NULL,
              url TEXT NOT NULL,
              position INTEGER NOT NULL,
              scroll_y INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun bookmarks(): List<Bookmark> = synchronized(lock) {
        val out = ArrayList<Bookmark>()
        readableDatabase.rawQuery(
            "SELECT id, title, url, created FROM bookmarks ORDER BY created DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(Bookmark(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
            }
        }
        out
    }

    fun isBookmarked(url: String): Boolean = synchronized(lock) {
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ? LIMIT 1", arrayOf(url)).use { it.moveToFirst() }
    }

    fun addBookmark(title: String, url: String) = synchronized(lock) {
        val values = ContentValues().apply {
            put("title", title.ifBlank { url })
            put("url", url)
            put("created", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("bookmarks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    fun removeBookmark(url: String) = synchronized(lock) {
        writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
        Unit
    }

    fun history(): List<HistoryEntry> = synchronized(lock) {
        val out = ArrayList<HistoryEntry>()
        readableDatabase.rawQuery(
            "SELECT id, title, url, visited FROM history ORDER BY visited DESC LIMIT 300",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(HistoryEntry(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
            }
        }
        out
    }

    fun addHistory(title: String, url: String) = synchronized(lock) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.rawQuery("SELECT id, visited FROM history ORDER BY visited DESC LIMIT 1", null).use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val visited = cursor.getLong(1)
                val same = db.rawQuery("SELECT url FROM history WHERE id = ?", arrayOf(id.toString())).use { row ->
                    row.moveToFirst() && row.getString(0) == url
                }
                if (same && now - visited < 60_000) {
                    val values = ContentValues().apply {
                        put("title", title.ifBlank { url })
                        put("visited", now)
                    }
                    db.update("history", values, "id = ?", arrayOf(id.toString()))
                    return
                }
            }
        }
        val values = ContentValues().apply {
            put("title", title.ifBlank { url })
            put("url", url)
            put("visited", now)
        }
        db.insert("history", null, values)
        db.execSQL(
            "DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visited DESC LIMIT 500)",
        )
    }

    fun removeHistory(id: Long) = synchronized(lock) {
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        Unit
    }

    fun clearHistory() = synchronized(lock) {
        writableDatabase.delete("history", null, null)
        Unit
    }

    fun loadTabs(): List<TabRecord> = synchronized(lock) {
        val out = ArrayList<TabRecord>()
        readableDatabase.rawQuery(
            "SELECT id, title, url, position, scroll_y FROM tabs ORDER BY position ASC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    TabRecord(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        url = cursor.getString(2),
                        position = cursor.getInt(3),
                        scrollY = cursor.getInt(4),
                    ),
                )
            }
        }
        out
    }

    fun saveTabs(tabs: List<TabRecord>) = synchronized(lock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("tabs", null, null)
            tabs.forEach { tab ->
                val values = ContentValues().apply {
                    put("id", tab.id)
                    put("title", tab.title)
                    put("url", tab.url)
                    put("position", tab.position)
                    put("scroll_y", tab.scrollY)
                }
                db.insert("tabs", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
