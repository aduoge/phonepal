package dev.xiaoman.memory

import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [MemoryStore] backed by a folder of Basic Memory Markdown files: note `a/b` lives in `<root>/a/b.md`.
 *
 * Files may be added or edited by other tools (adb push, Obsidian); every lookup rescans the folder
 * by mtime/size so the search index follows them. Writes are atomic (temp file + rename), UTF-8,
 * and stamp the file's mtime with [clock]. Hidden files and folders (`.obsidian`) are ignored.
 */
class FileMemoryStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : MemoryStore {
    private val mutex = Mutex()
    private val index = MemoryIndex(root)
    private val canonicalRoot by lazy { root.canonicalFile }

    override suspend fun write(note: Note): Note = locked { writeLocked(note) }

    override suspend fun read(identifier: String): Note? = locked { readLocked(identifier) }

    override suspend fun search(query: String, limit: Int): List<SearchHit> = locked {
        index.sync()
        index.search(query, limit)
    }

    override suspend fun appendObservation(identifier: String, observation: Observation): Note = locked {
        val existing = readLocked(identifier)
        if (existing != null) {
            writeLocked(existing.copy(observations = existing.observations + observation))
        } else {
            val permalink = Permalinks.normalize(identifier)
            writeLocked(Note(title = permalink.substringAfterLast('/'), permalink = permalink, observations = listOf(observation)))
        }
    }

    override suspend fun delete(identifier: String): Boolean = locked {
        val note = readLocked(identifier) ?: return@locked false
        val file = fileFor(note.permalink)
        val deleted = file.delete()
        index.remove(note.permalink)
        pruneEmptyFolders(file.parentFile)
        deleted
    }

    override suspend fun list(folder: String?): List<Note> = locked {
        index.sync()
        val prefix = folder?.trim()?.replace('\\', '/')?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "$it/" }
        index.notes.filter { prefix == null || it.permalink.startsWith(prefix) }.sortedBy { it.permalink }
    }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun writeLocked(note: Note): Note {
        val title = note.title.trim().ifEmpty { note.permalink.trim().removeSuffix(".md").substringAfterLast('/').trim() }
        require(title.isNotEmpty()) { "Note needs a title or a permalink" }
        val permalink = if (note.permalink.isBlank()) generatePermalink(title) else Permalinks.normalize(note.permalink)
        val file = fileFor(permalink)
        val text = BasicMemoryFormat.render(note.copy(title = title, permalink = permalink))
        atomicWrite(file, text)
        file.setLastModified(clock())
        val stored = BasicMemoryFormat.parse(text, permalink, file.lastModified())
        index.put(stored, file.lastModified(), file.length())
        return stored
    }

    private fun readLocked(identifier: String): Note? {
        val key = identifier.trim()
        if (key.isEmpty()) return null
        val permalink = runCatching { Permalinks.normalize(key) }.getOrNull()
        if (permalink != null) {
            val file = fileFor(permalink)
            if (file.isFile) return index.load(permalink, file)
        }
        index.sync()
        val notes = index.notes.sortedBy { it.permalink }
        return notes.firstOrNull { it.title == key } ?: notes.firstOrNull { it.title.equals(key, ignoreCase = true) }
    }

    /** Slug of [title], suffixed `-2`, `-3`, … when another note already owns it. */
    private fun generatePermalink(title: String): String {
        val base = Permalinks.fromTitle(title).ifEmpty { "note-${clock()}" }
        var candidate = base
        var n = 2
        while (true) {
            val file = fileFor(candidate)
            if (!file.exists()) return candidate
            if (file.isFile && index.load(candidate, file)?.title == title) return candidate
            candidate = "$base-${n++}"
        }
    }

    private fun fileFor(permalink: String): File {
        val file = File(root, "$permalink.md")
        require(file.canonicalFile.toPath().startsWith(canonicalRoot.toPath())) { "Permalink escapes the memory folder: $permalink" }
        return file
    }

    private fun atomicWrite(file: File, text: String) {
        val dir = file.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create folder $dir")
        val temp = File.createTempFile(".${file.name}.", ".tmp", dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun pruneEmptyFolders(start: File?) {
        var dir = start?.canonicalFile ?: return
        while (dir != canonicalRoot && dir.toPath().startsWith(canonicalRoot.toPath())) {
            if (dir.list()?.isEmpty() != true || !dir.delete()) return
            dir = dir.parentFile ?: return
        }
    }
}
