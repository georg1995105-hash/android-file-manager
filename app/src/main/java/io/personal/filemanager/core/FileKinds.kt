package io.personal.filemanager.core

enum class FileKind {
    FOLDER, IMAGE, VIDEO, AUDIO, PDF, TEXT, DOCUMENT, ARCHIVE, APK, OTHER
}

object FileTypes {
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif", "dng")
    val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "m2ts", "wmv", "flv")
    val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "amr", "mid", "midi")

    /** Форматы со встроенным текстовым просмотром. */
    val textExtensions = setOf(
        "md", "markdown", "txt", "json", "yaml", "yml", "xml", "csv", "tsv", "log", "ini", "conf", "cfg",
        "properties", "toml", "env", "kt", "kts", "java", "py", "js", "ts", "sh", "bash", "html", "htm",
        "css", "sql", "gradle", "srt", "vtt", "ass", "nfo",
    )

    val officeExtensions = setOf(
        "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub", "fb2", "djvu",
    )

    /** Документы для категории «Документы» (офис, PDF и основные текстовые форматы). */
    val documentExtensions = officeExtensions + setOf("pdf", "txt", "md", "csv", "json", "yaml", "yml", "xml", "log")

    val archiveExtensions = setOf(
        "zip", "zipx", "7z", "rar", "tar", "gz", "tgz", "xz", "txz", "zst", "tzst", "bz2", "tbz2", "lz4", "lzma",
        "cab", "iso",
    )

    val apkExtensions = setOf("apk", "apks", "xapk", "apkm")

    private val multipartArchive = Regex(""".*\.(7z|zip|rar)\.\d{3}$|.*\.z\d{2}$|.*\.r\d{2}$""", RegexOption.IGNORE_CASE)

    /** Подстроки для поиска по MediaStore (LIKE '%.ext'). */
    val archiveLikePatterns: List<String> =
        archiveExtensions.map { "%.$it" } + listOf("%.7z.0__", "%.zip.0__", "%.z0_", "%.part_.rar")

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return ""
        return name.substring(dot + 1).lowercase()
    }

    fun isMultipartArchive(name: String): Boolean = multipartArchive.matches(name)

    fun kindOf(name: String, isDirectory: Boolean = false): FileKind {
        if (isDirectory) return FileKind.FOLDER
        val ext = extensionOf(name)
        return when {
            ext in imageExtensions -> FileKind.IMAGE
            ext in videoExtensions -> FileKind.VIDEO
            ext in audioExtensions -> FileKind.AUDIO
            ext == "pdf" -> FileKind.PDF
            ext in apkExtensions -> FileKind.APK
            ext in archiveExtensions || isMultipartArchive(name) -> FileKind.ARCHIVE
            ext in textExtensions -> FileKind.TEXT
            ext in officeExtensions -> FileKind.DOCUMENT
            else -> FileKind.OTHER
        }
    }

    /** MIME для форматов, которые Android часто не знает. null — определять системой. */
    fun fallbackMime(ext: String): String? = when (ext) {
        "md", "markdown" -> "text/markdown"
        "yaml", "yml" -> "application/yaml"
        "json" -> "application/json"
        "log", "ini", "conf", "cfg", "properties", "toml", "env", "nfo" -> "text/plain"
        "kt", "kts", "java", "py", "sh", "bash", "gradle", "sql" -> "text/plain"
        "csv" -> "text/csv"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "apk" -> "application/vnd.android.package-archive"
        "apks", "xapk", "apkm" -> "application/octet-stream"
        "7z" -> "application/x-7z-compressed"
        "rar" -> "application/vnd.rar"
        "zst", "tzst" -> "application/zstd"
        "xz", "txz" -> "application/x-xz"
        "mkv" -> "video/x-matroska"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        else -> null
    }
}
