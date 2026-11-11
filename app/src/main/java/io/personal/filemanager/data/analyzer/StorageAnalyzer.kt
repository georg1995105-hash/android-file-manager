package io.personal.filemanager.data.analyzer

import io.personal.filemanager.core.FileKind
import io.personal.filemanager.core.FileTypes
import io.personal.filemanager.data.ops.TrashStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.concurrent.CancellationException

data class AnalyzedFile(val path: String, val bytes: Long) {
    val name: String get() = File(path).name
}
data class CategorySize(val kind: FileKind, val bytes: Long, val count: Int)
data class FolderUsage(val path: String, val bytes: Long, val count: Int)
data class DuplicateGroup(val files: List<AnalyzedFile>, val bytesPerFile: Long) {
    val reclaimableBytes: Long get() = bytesPerFile * (files.size - 1)
}
data class AnalysisResult(
    val scannedFiles: Int, val scannedBytes: Long, val skippedDirectories: Int,
    val incomplete: Boolean, val duplicatesIncomplete: Boolean,
    val categories: List<CategorySize>, val largeFiles: List<AnalyzedFile>,
    val duplicates: List<DuplicateGroup>,
    val topFolders: List<FolderUsage> = emptyList(),
    val categoryFiles: Map<FileKind, List<AnalyzedFile>> = emptyMap(),
)

/** Ограниченный, отменяемый анализ доступных файлов. Ничего не удаляет. */
class StorageAnalyzer {
    fun scan(
        root: File, onProgress: (Int) -> Unit = {},
        cancelled: () -> Boolean = { false }, maxFiles: Int = 150_000,
        hashBudget: Long = 2L * 1024 * 1024 * 1024,
    ): AnalysisResult {
        require(maxFiles > 0 && hashBudget >= 0)
        fun check() { if (cancelled()) throw CancellationException("Анализ отменён") }
        val counts = mutableMapOf<FileKind, Int>()
        val sizes = mutableMapOf<FileKind, Long>()
        val candidates = HashMap<Long, MutableList<AnalyzedFile>>()
        val top = PriorityQueue<AnalyzedFile>(compareBy { it.bytes })
        val topByKind = HashMap<FileKind, PriorityQueue<AnalyzedFile>>()
        val directFolderBytes = HashMap<String, Long>()
        val directFolderCounts = HashMap<String, Int>()
        val dirs = ArrayDeque<File>()
        dirs.add(root)
        var scanned = 0
        var total = 0L
        var skipped = 0
        var incomplete = false
        while (dirs.isNotEmpty()) {
            check()
            val dir = dirs.removeLast()
            if (Files.isSymbolicLink(dir.toPath())) { skipped++; continue }
            val children = try { dir.listFiles() } catch (_: SecurityException) { null }
            if (children == null) { skipped++; continue }
            for (file in children) {
                check()
                if (Files.isSymbolicLink(file.toPath())) { skipped++; continue }
                if (file.isDirectory) {
                    if (file.name == TrashStore.TRASH_DIR || file.name == ".pfm-trash") continue
                    dirs.add(file)
                } else if (file.isFile) {
                    val size = file.length().coerceAtLeast(0L)
                    val kind = FileTypes.kindOf(file.name)
                    counts[kind] = (counts[kind] ?: 0) + 1
                    sizes[kind] = (sizes[kind] ?: 0L) + size
                    val entry = AnalyzedFile(file.absolutePath, size)
                    val folderPath = dir.absolutePath
                    directFolderBytes[folderPath] = (directFolderBytes[folderPath] ?: 0L) + size
                    directFolderCounts[folderPath] = (directFolderCounts[folderPath] ?: 0) + 1
                    val perKind = topByKind.getOrPut(kind) {
                        PriorityQueue(compareBy<AnalyzedFile> { it.bytes })
                    }
                    if (perKind.size < 25) perKind.add(entry)
                    else if (size > perKind.peek().bytes) { perKind.poll(); perKind.add(entry) }
                    if (size > 0L) candidates.getOrPut(size) { ArrayList() }.add(entry)
                    if (top.size < 25) top.add(entry)
                    else if (size > top.peek().bytes) { top.poll(); top.add(entry) }
                    total += size
                    scanned++
                    if (scanned % 500 == 0) onProgress(scanned)
                    if (scanned >= maxFiles) { incomplete = true; break }
                }
            }
            if (incomplete) break
        }
        onProgress(scanned)
        val same = HashMap<Pair<Long, String>, MutableList<AnalyzedFile>>()
        var bytesHashed = 0L
        var filesHashed = 0
        var partialDuplicates = incomplete
        for ((size, files) in candidates.entries.asSequence().filter { it.value.size > 1 }.sortedBy { it.key }) {
            check()
            for (file in files) {
                check()
                if (size > 512L * 1024 * 1024 || bytesHashed + size > hashBudget || filesHashed >= 3_000) {
                    partialDuplicates = true
                    continue
                }
                try {
                    val md = MessageDigest.getInstance("SHA-256")
                    File(file.path).inputStream().buffered().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            check()
                            val n = input.read(buffer)
                            if (n < 0) break
                            md.update(buffer, 0, n)
                        }
                    }
                    if (!File(file.path).isFile || File(file.path).length() != size) {
                        partialDuplicates = true
                        continue
                    }
                    val hash = md.digest().joinToString("") { "%02x".format(it) }
                    same.getOrPut(size to hash) { ArrayList() }.add(file)
                    bytesHashed += size
                    filesHashed++
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { partialDuplicates = true }
            }
        }
        // Aggregate direct folder sizes through parent directories once per folder.
        // This is substantially cheaper than walking ancestors for every file.
        val folderBytes = HashMap<String, Long>()
        val folderCounts = HashMap<String, Int>()
        val rootPath = root.absolutePath
        for ((dirPath, bytes) in directFolderBytes) {
            check()
            var parent: File? = File(dirPath)
            val count = directFolderCounts[dirPath] ?: 0
            while (parent != null) {
                val path = parent.absolutePath
                if (path != rootPath && !path.startsWith("$rootPath/")) break
                folderBytes[path] = (folderBytes[path] ?: 0L) + bytes
                folderCounts[path] = (folderCounts[path] ?: 0) + count
                if (path == rootPath) break
                parent = parent.parentFile
            }
        }
        return AnalysisResult(
            scanned, total, skipped, incomplete || skipped > 0, partialDuplicates,
            counts.map { (kind, n) -> CategorySize(kind, sizes[kind] ?: 0L, n) }.sortedByDescending { it.bytes },
            top.toList().sortedByDescending { it.bytes },
            same.entries.mapNotNull { (key, entries) ->
                if (entries.size < 2) null else DuplicateGroup(entries, key.first)
            }.sortedByDescending { it.reclaimableBytes }.take(30),
            folderBytes.entries.asSequence()
                .filter { it.key != rootPath }
                .map { FolderUsage(it.key, it.value, folderCounts[it.key] ?: 0) }
                .sortedByDescending { it.bytes }.take(30).toList(),
            topByKind.mapValues { (_, queue) -> queue.toList().sortedByDescending { it.bytes } },
        )
    }
}