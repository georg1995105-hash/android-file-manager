package io.personal.filemanager.data.archive

import java.io.File

/** Поддерживаемые встроенным движком форматы (ТЗ §5). RAR — только чтение RAR 1.5–4.x. */
enum class ArchiveFormat(val title: String, val canCreate: Boolean = false) {
    ZIP("ZIP", canCreate = true),
    SEVEN_Z("7z", canCreate = true),
    RAR("RAR"),
    TAR("TAR"),
    TAR_GZ("TAR.GZ"),
    TAR_XZ("TAR.XZ"),
    TAR_ZST("TAR.ZST"),
    TAR_BZ2("TAR.BZ2"),
    GZ("GZ"),
    XZ("XZ"),
    ZST("ZST"),
    BZ2("BZ2"),
}

data class ArchiveItem(
    /** Нормализованный относительный путь внутри архива, разделитель «/». */
    val path: String,
    val isDirectory: Boolean,
    /** -1 — размер неизвестен до распаковки. */
    val size: Long,
    val packedSize: Long,
    val modified: Long,
    val encrypted: Boolean,
) {
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = path.substringBeforeLast('/', "")
}

sealed interface ListResult {
    data class Ok(val items: List<ArchiveItem>, val encrypted: Boolean, val note: String? = null) : ListResult
    data object PasswordRequired : ListResult
    data object WrongPassword : ListResult
    data class Unsupported(val reason: String) : ListResult
    data class Failed(val reason: String) : ListResult
}

/** Пределы распаковки: защита от «архивных бомб» и переполнения памяти телефона. */
data class ExtractLimits(
    val maxTotalBytes: Long,
    val maxEntries: Int = 200_000,
    /** Подозрительная степень сжатия (распаковано / размер архива). */
    val maxRatio: Long = 1_000,
    /** Степень сжатия проверяется после этого объёма распакованных данных. */
    val ratioCheckAfterBytes: Long = 200_000_000,
)

data class ExtractResult(
    val outputDir: File? = null,
    val files: Int = 0,
    val bytes: Long = 0,
    /** Пропущенные элементы с причиной (небезопасный путь, ссылка и т. п.). */
    val skipped: List<Pair<String, String>> = emptyList(),
    val error: String? = null,
    val cancelled: Boolean = false,
    val passwordRequired: Boolean = false,
    val wrongPassword: Boolean = false,
) {
    val ok: Boolean get() = error == null && !cancelled && !passwordRequired && !wrongPassword && outputDir != null
}

class ArchiveAbort(message: String) : Exception(message)
