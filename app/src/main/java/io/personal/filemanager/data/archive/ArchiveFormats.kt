package io.personal.filemanager.data.archive

import java.io.File

object ArchiveFormats {
    private val sevenZPart = Regex("""^(.*\.7z)\.(\d{3})$""", RegexOption.IGNORE_CASE)
    private val zipPart = Regex("""^(.*)\.z(\d{2})$""", RegexOption.IGNORE_CASE)
    private val rarPart = Regex("""^(.*)\.part(\d+)\.rar$""", RegexOption.IGNORE_CASE)
    private val rarOldPart = Regex("""^(.*)\.r(\d{2})$""", RegexOption.IGNORE_CASE)

    /** Формат по имени (с учётом многотомных архивов). null — не архив или не поддерживается. */
    fun detect(name: String): ArchiveFormat? {
        val n = name.lowercase()
        return when {
            sevenZPart.matches(n) || n.endsWith(".7z") -> ArchiveFormat.SEVEN_Z
            n.endsWith(".zip") || n.endsWith(".zipx") || zipPart.matches(n) -> ArchiveFormat.ZIP
            n.endsWith(".rar") || rarOldPart.matches(n) -> ArchiveFormat.RAR
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> ArchiveFormat.TAR_XZ
            n.endsWith(".tar.zst") || n.endsWith(".tzst") -> ArchiveFormat.TAR_ZST
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> ArchiveFormat.TAR_BZ2
            n.endsWith(".tar") -> ArchiveFormat.TAR
            n.endsWith(".gz") -> ArchiveFormat.GZ
            n.endsWith(".xz") -> ArchiveFormat.XZ
            n.endsWith(".zst") -> ArchiveFormat.ZST
            n.endsWith(".bz2") -> ArchiveFormat.BZ2
            else -> null
        }
    }

    /**
     * Файл, с которого открывается многотомный архив: `.7z.002` → `.7z.001`, `.z01` → `.zip`,
     * `.part2.rar` → `.part1.rar` (или `.part01.rar`), `.r00` → `.rar`.
     */
    fun primaryVolume(file: File): File {
        val dir = file.parentFile ?: return file
        val name = file.name
        sevenZPart.matchEntire(name)?.let { m -> return File(dir, "${m.groupValues[1]}.001").takeIf { it.exists() } ?: file }
        zipPart.matchEntire(name)?.let { m -> return File(dir, "${m.groupValues[1]}.zip").takeIf { it.exists() } ?: file }
        rarPart.matchEntire(name)?.let { m ->
            val digits = m.groupValues[2].length
            return File(dir, "${m.groupValues[1]}.part${"1".padStart(digits, '0')}.rar").takeIf { it.exists() } ?: file
        }
        rarOldPart.matchEntire(name)?.let { m -> return File(dir, "${m.groupValues[1]}.rar").takeIf { it.exists() } ?: file }
        return file
    }

    /** Все тома `.7z.001`, `.7z.002`… подряд; для обычного `.7z` — сам файл. */
    fun sevenZVolumes(first: File): List<File> {
        val m = sevenZPart.matchEntire(first.name) ?: return listOf(first)
        val dir = first.parentFile ?: return listOf(first)
        val volumes = mutableListOf<File>()
        var i = m.groupValues[2].toInt()
        while (true) {
            val f = File(dir, "${m.groupValues[1]}.${i.toString().padStart(3, '0')}")
            if (!f.isFile) break
            volumes += f
            i++
        }
        return volumes.ifEmpty { listOf(first) }
    }

    /** Имя папки для распаковки: «photos.tar.gz» → «photos», «big.7z.001» → «big». */
    fun baseName(file: File): String {
        var n = file.name
        sevenZPart.matchEntire(n)?.let { n = it.groupValues[1] }
        rarPart.matchEntire(n)?.let { n = it.groupValues[1] }
        val lower = n.lowercase()
        val ext = listOf(".tar.gz", ".tar.xz", ".tar.zst", ".tar.bz2", ".tgz", ".txz", ".tzst", ".tbz2", ".tbz",
            ".zipx", ".zip", ".7z", ".rar", ".tar", ".gz", ".xz", ".zst", ".bz2")
            .firstOrNull { lower.endsWith(it) }
        return (if (ext != null) n.dropLast(ext.length) else n).ifBlank { "archive" }
    }

    /**
     * Безопасный относительный путь элемента архива или null (Zip Slip, абсолютный путь, «..»,
     * буква диска, нулевой символ).
     */
    fun safeRelativePath(raw: String): String? {
        val n = raw.replace('\\', '/')
        if (n.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(n)) return null
        if (n.contains('\u0000')) return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** Окончательная проверка: путь назначения лежит строго внутри папки распаковки. */
    fun isInside(target: File, root: File): Boolean {
        val t = target.canonicalPath
        val r = root.canonicalPath
        return t.startsWith("$r/")
    }
}
