package io.personal.filemanager.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Локальная база собственных данных (заметки, ссылки, избранное).
 * Схема версионируется; изменения — только через явные миграции в [onUpgrade], без удаления данных.
 * Файл базы исключён из системного облачного бэкапа (data_extraction_rules), перенос — через явную копию.
 */
class AppDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        createV1(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Пример будущей миграции: if (oldVersion < 2) db.execSQL("ALTER TABLE notes ADD COLUMN color TEXT")
        // Destructive-миграции запрещены (ТЗ §8).
    }

    private fun createV1(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL DEFAULT '',
                created INTEGER NOT NULL,
                updated INTEGER NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL(
            """CREATE TABLE links (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL DEFAULT '',
                url TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                created INTEGER NOT NULL,
                updated INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE favorites (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                is_dir INTEGER NOT NULL,
                added INTEGER NOT NULL)""",
        )
    }

    companion object {
        const val NAME = "personal.db"
        const val VERSION = 1
    }
}

/** Репозиторий личных данных с реактивными списками (обновляются после каждой записи). */
class PersonalRepository(context: Context) {
    private val helper = AppDatabase(context)
    private val version = MutableStateFlow(0)

    private fun changed() {
        version.value++
    }

    private fun <T> observe(query: (SQLiteDatabase) -> T): Flow<T> =
        version.map { query(helper.readableDatabase) }.flowOn(Dispatchers.IO)

    // ---------- Заметки ----------

    val notes: Flow<List<Note>> = observe { db ->
        db.rawQuery("SELECT * FROM notes ORDER BY pinned DESC, updated DESC", null).use { c -> c.map(::readNote) }
    }

    suspend fun note(id: Long): Note? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT * FROM notes WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) readNote(c) else null
        }
    }

    /** Сохраняет заметку; возвращает её id (новый для id == 0). */
    suspend fun saveNote(note: Note): Long = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put("title", note.title)
            put("body", note.body)
            put("created", note.created)
            put("updated", note.updated)
            put("pinned", if (note.pinned) 1 else 0)
        }
        val db = helper.writableDatabase
        val id = if (note.id == 0L) db.insertOrThrow("notes", null, v) else {
            db.update("notes", v, "id = ?", arrayOf(note.id.toString()))
            note.id
        }
        changed()
        id
    }

    suspend fun deleteNote(id: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("notes", "id = ?", arrayOf(id.toString()))
        changed()
    }

    // ---------- Ссылки ----------

    val links: Flow<List<Link>> = observe { db ->
        db.rawQuery("SELECT * FROM links ORDER BY updated DESC", null).use { c -> c.map(::readLink) }
    }

    suspend fun saveLink(link: Link): Long = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put("title", link.title)
            put("url", link.url)
            put("note", link.note)
            put("created", link.created)
            put("updated", link.updated)
        }
        val db = helper.writableDatabase
        val id = if (link.id == 0L) db.insertOrThrow("links", null, v) else {
            db.update("links", v, "id = ?", arrayOf(link.id.toString()))
            link.id
        }
        changed()
        id
    }

    suspend fun deleteLink(id: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("links", "id = ?", arrayOf(id.toString()))
        changed()
    }

    // ---------- Избранное ----------

    val favorites: Flow<List<Favorite>> = observe { db ->
        db.rawQuery("SELECT * FROM favorites ORDER BY added DESC", null).use { c -> c.map(::readFavorite) }
    }

    /** Добавляет или убирает путь; возвращает true, если теперь в избранном. */
    suspend fun toggleFavorite(path: String, name: String, isDirectory: Boolean): Boolean = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val removed = db.delete("favorites", "path = ?", arrayOf(path))
        if (removed == 0) {
            db.insertOrThrow(
                "favorites",
                null,
                ContentValues().apply {
                    put("path", path)
                    put("name", name)
                    put("is_dir", if (isDirectory) 1 else 0)
                    put("added", System.currentTimeMillis())
                },
            )
        }
        changed()
        removed == 0
    }

    suspend fun removeFavorite(id: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("favorites", "id = ?", arrayOf(id.toString()))
        changed()
    }

    /**
     * Объект переименован/перемещён через наше приложение: обновляются пути избранного,
     * включая вложенные в перемещённую папку.
     */
    suspend fun relocateFavoriteTree(oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val affected = db.rawQuery("SELECT * FROM favorites", null).use { c -> c.map(::readFavorite) }
            .filter { it.path == oldPath || it.path.startsWith("$oldPath/") }
        if (affected.isEmpty()) return@withContext
        db.beginTransaction()
        try {
            affected.forEach { f ->
                val np = newPath + f.path.removePrefix(oldPath)
                val v = ContentValues().apply {
                    put("path", np)
                    put("name", np.substringAfterLast('/'))
                }
                db.updateWithOnConflict("favorites", v, "id = ?", arrayOf(f.id.toString()), SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    // ---------- Снимок и восстановление (резервные копии) ----------

    suspend fun snapshot(): Triple<List<Note>, List<Link>, List<Favorite>> = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        Triple(
            db.rawQuery("SELECT * FROM notes ORDER BY id", null).use { it.map(::readNote) },
            db.rawQuery("SELECT * FROM links ORDER BY id", null).use { it.map(::readLink) },
            db.rawQuery("SELECT * FROM favorites ORDER BY id", null).use { it.map(::readFavorite) },
        )
    }

    /**
     * Полная замена данных в одной транзакции: при любой ошибке база остаётся прежней.
     */
    suspend fun replaceAll(notes: List<Note>, links: List<Link>, favorites: List<Favorite>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("notes", null, null)
            db.delete("links", null, null)
            db.delete("favorites", null, null)
            notes.forEach { n ->
                db.insertOrThrow("notes", null, ContentValues().apply {
                    put("title", n.title); put("body", n.body); put("created", n.created); put("updated", n.updated)
                    put("pinned", if (n.pinned) 1 else 0)
                })
            }
            links.forEach { l ->
                db.insertOrThrow("links", null, ContentValues().apply {
                    put("title", l.title); put("url", l.url); put("note", l.note); put("created", l.created); put("updated", l.updated)
                })
            }
            favorites.distinctBy { it.path }.forEach { f ->
                db.insertOrThrow("favorites", null, ContentValues().apply {
                    put("path", f.path); put("name", f.name); put("is_dir", if (f.isDirectory) 1 else 0); put("added", f.added)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    private fun <T> Cursor.map(read: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count)
        while (moveToNext()) out += read(this)
        return out
    }

    private fun Cursor.str(name: String) = getString(getColumnIndexOrThrow(name)) ?: ""
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))

    private fun readNote(c: Cursor) = Note(c.long("id"), c.str("title"), c.str("body"), c.long("created"), c.long("updated"), c.long("pinned") != 0L)
    private fun readLink(c: Cursor) = Link(c.long("id"), c.str("title"), c.str("url"), c.str("note"), c.long("created"), c.long("updated"))
    private fun readFavorite(c: Cursor) = Favorite(c.long("id"), c.str("path"), c.str("name"), c.long("is_dir") != 0L, c.long("added"))
}
