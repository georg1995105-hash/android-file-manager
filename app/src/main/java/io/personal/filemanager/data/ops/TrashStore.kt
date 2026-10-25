package io.personal.filemanager.data.ops

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.random.Random

@Serializable
data class TrashMeta(
    val originalPath: String,
    val name: String,
    val deletedAt: Long,
    val isDirectory: Boolean,
    val size: Long,
)

data class TrashItem(val id: String, val dir: File, val meta: TrashMeta) {
    val payload: File get() = File(File(dir, TrashStore.PAYLOAD), meta.name)
}

/**
 * Корзина приложения: файл переносится (rename, без копирования) в скрытую папку
 * `.FileManagerTrash` в корне того же носителя. Восстановление возвращает его на место.
 * Работает только там, где возможен rename в пределах носителя; иначе удаление
 * предлагается как необратимое с отдельным подтверждением.
 */
class TrashStore(private val trashRootFor: (File) -> File?) {
    private val json = Json { ignoreUnknownKeys = true }

    fun isInTrash(file: File): Boolean = file.absolutePath.split('/').contains(TRASH_DIR)

    fun moveToTrash(file: File): Result<TrashItem> = runCatching {
        require(file.exists()) { "Файл не найден" }
        val root = trashRootFor(file) ?: error("Для этого носителя корзина недоступна")
        require(!isInTrash(file)) { "Файл уже в корзине" }
        if (!root.isDirectory && !root.mkdirs()) error("Не удалось создать папку корзины")
        File(root, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        val id = "${System.currentTimeMillis()}-${Random.nextInt(0, Int.MAX_VALUE).toString(36)}"
        val dir = File(root, id)
        val payloadDir = File(dir, PAYLOAD)
        if (!payloadDir.mkdirs()) error("Не удалось подготовить корзину")
        val meta = TrashMeta(
            originalPath = file.absolutePath,
            name = file.name,
            deletedAt = System.currentTimeMillis(),
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) treeSize(file) else file.length(),
        )
        File(dir, META).writeText(json.encodeToString(TrashMeta.serializer(), meta))
        if (!file.renameTo(File(payloadDir, file.name))) {
            dir.deleteRecursively()
            error("Не удалось переместить в корзину (другой носитель?)")
        }
        TrashItem(id, dir, meta)
    }

    fun list(roots: Collection<File>): List<TrashItem> = roots.flatMap { root ->
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val meta = runCatching { json.decodeFromString(TrashMeta.serializer(), File(dir, META).readText()) }.getOrNull()
                ?: return@mapNotNull null
            TrashItem(dir.name, dir, meta).takeIf { it.payload.exists() }
        }
    }.sortedByDescending { it.meta.deletedAt }

    /** Возвращает на исходное место; занятое имя не перезаписывается — выбирается «имя (1)». */
    fun restore(item: TrashItem): Result<File> = runCatching {
        val original = File(item.meta.originalPath)
        val parent = original.parentFile ?: error("Неизвестная исходная папка")
        if (!parent.isDirectory && !parent.mkdirs()) error("Не удалось восстановить исходную папку")
        val target = if (original.exists()) uniqueTarget(parent, original.name) else original
        if (!item.payload.renameTo(target)) error("Не удалось восстановить файл")
        item.dir.deleteRecursively()
        target
    }

    /** Окончательное удаление одного элемента корзины. */
    fun purge(item: TrashItem): Boolean = item.dir.deleteRecursively()

    companion object {
        const val TRASH_DIR = ".FileManagerTrash"
        internal const val PAYLOAD = "payload"
        private const val META = "meta.json"

        fun treeSize(file: File): Long =
            if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()
    }
}
