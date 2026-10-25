package io.personal.filemanager.core

/**
 * Разделы приложения. Имя enum — стабильный ключ хранения в DataStore: не переименовывать.
 * «На ТВ» исключён по ТЗ (§2), хотя присутствует на макете.
 */
enum class SectionId(
    val title: String,
    val defaultIcon: String,
    val defaultColor: String,
    /** Пять предлагаемых значков (как в макете «Иконки разделов»). Первый — по умолчанию. */
    val suggestedIcons: List<String>,
) {
    DOWNLOADS("Загрузки", "download", "blue", listOf("download", "cloud_download", "file_download", "save_alt", "arrow_downward")),
    RECENT("Новые", "schedule", "green", listOf("schedule", "event", "access_time", "history", "star")),
    PHOTOS("Фото", "image", "purple", listOf("image", "photo", "landscape", "photo_camera", "camera")),
    VIDEOS("Видео", "movie", "red", listOf("movie", "play_arrow", "videocam", "movie_creation", "play_circle")),
    DOCUMENTS("Документы", "description", "blue_light", listOf("description", "notes", "article", "menu_book", "text_snippet")),
    ARCHIVES("Архивы", "folder_zip", "orange", listOf("folder_zip", "folder", "inventory", "storage", "archive")),
    APPS("Приложения", "android", "green", listOf("android", "apps", "install_mobile", "widgets", "smartphone")),
    NOTES("Заметки", "sticky_note", "orange", listOf("sticky_note", "edit_note", "notes", "description", "bookmark")),
    LINKS("Ссылки", "link", "gray", listOf("link", "public", "language", "bookmark", "attach_file")),
    FAVORITES("Избранное", "star", "orange", listOf("star", "favorite", "bookmark", "grade", "push_pin")),
    FILES("Файлы", "folder", "blue", listOf("folder", "folder_open", "smartphone", "sd_storage", "home")),
    ;

    companion object {
        fun fromKey(key: String): SectionId? = entries.firstOrNull { it.name == key }
    }
}

object IconKeys {
    /** Полный встроенный набор значков (ключ → отображается в ui/icons/IconCatalog). */
    val all: List<String> = listOf(
        "download", "cloud_download", "file_download", "save_alt", "arrow_downward",
        "schedule", "event", "access_time", "history", "new_releases",
        "image", "photo", "landscape", "photo_camera", "camera", "photo_library",
        "movie", "play_arrow", "videocam", "movie_creation", "play_circle", "video_library",
        "description", "notes", "article", "menu_book", "text_snippet", "picture_as_pdf",
        "folder_zip", "folder", "folder_open", "inventory", "storage", "archive", "unarchive",
        "android", "apps", "install_mobile", "widgets", "smartphone",
        "sticky_note", "edit_note", "bookmark", "attach_file", "push_pin",
        "link", "public", "language", "code", "terminal", "data_object",
        "star", "favorite", "grade", "home", "work", "lock", "music_note", "sd_storage", "cloud", "inbox",
    )
}

object ColorKeys {
    /** Встроенная палитра. Значения цветов — в ui/theme/AccentColors. */
    val all: List<String> = listOf(
        "blue", "blue_light", "cyan", "teal", "green", "lime", "orange", "red", "pink", "purple", "gray", "white",
    )
}
