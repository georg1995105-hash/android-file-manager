package io.personal.filemanager.data.settings

import io.personal.filemanager.core.ColorKeys
import io.personal.filemanager.core.IconKeys
import io.personal.filemanager.core.SectionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val MAX_TABS = 5

@Serializable
data class TileConfig(
    val id: String,
    val visible: Boolean,
    val icon: String,
    val color: String,
)

@Serializable
data class TabConfig(
    val id: String,
    val icon: String,
    val color: String,
)

/** Кнопки нижней панели просмотра фото/видео (порядок и видимость настраиваются). */
enum class ViewerControl(val title: String, val defaultVisible: Boolean) {
    PLAY_PAUSE("Пуск/пауза (видео)", true),
    SHARE("Поделиться", true),
    ROTATE("Поворот экрана", true),
    FULLSCREEN("Полный экран", true),
    INFO("Сведения", true),
    DELETE("В корзину", true),
    PREV("Предыдущий файл", false),
    NEXT("Следующий файл", false),
    OPEN_EXTERNAL("Открыть в другом приложении", false),
    ;

    companion object {
        fun fromKey(key: String): ViewerControl? = entries.firstOrNull { it.name == key }
    }
}

@Serializable
data class ControlConfig(
    val id: String,
    val visible: Boolean,
)

/** Вид файлового раздела. */
enum class FileViewMode { LIST, GRID }

/**
 * Настройки вида файловых разделов: режим по разделу (ключ — «RECENT», «FILES», «PHOTOS», «SEARCH»…)
 * и число файлов в ряду сетки отдельно для телефона/планшета и вертикального/горизонтального положения.
 */
object FileViewPrefs {
    val PHONE_COLUMNS = 2..5
    val TABLET_COLUMNS = 3..7
    private val gridByDefault = setOf("PHOTOS", "VIDEOS")

    fun defaultMode(section: String): FileViewMode = if (section in gridByDefault) FileViewMode.GRID else FileViewMode.LIST
    fun range(tablet: Boolean): IntRange = if (tablet) TABLET_COLUMNS else PHONE_COLUMNS
    fun columnsKey(tablet: Boolean, landscape: Boolean): String =
        (if (tablet) "tablet" else "phone") + (if (landscape) "_landscape" else "_portrait")

    fun defaultColumns(tablet: Boolean, landscape: Boolean): Int = when {
        !tablet && !landscape -> 3
        !tablet -> 5
        !landscape -> 4
        else -> 6
    }

    val columnKeys: List<String> = listOf(false, true).flatMap { t -> listOf(false, true).map { l -> columnsKey(t, l) } }
    fun rangeForKey(key: String): IntRange = range(key.startsWith("tablet"))
}

fun UiConfig.viewMode(section: String): FileViewMode =
    viewModes[section]?.let { v -> FileViewMode.entries.firstOrNull { it.name == v } } ?: FileViewPrefs.defaultMode(section)

fun UiConfig.gridColumns(tablet: Boolean, landscape: Boolean): Int =
    gridColumns[FileViewPrefs.columnsKey(tablet, landscape)]?.takeIf { it in FileViewPrefs.range(tablet) }
        ?: FileViewPrefs.defaultColumns(tablet, landscape)

/** Плиток в ряду на главной: 0 — автоматически по ширине экрана. */
const val TILES_PER_ROW_AUTO = 0
val TILES_PER_ROW_OPTIONS = listOf(TILES_PER_ROW_AUTO, 2, 3, 4, 5, 6)

/** Персонализация главной и нижней панели. version — для будущих миграций формата. */
@Serializable
data class UiConfig(
    val version: Int = CURRENT_VERSION,
    val tiles: List<TileConfig>,
    val tabs: List<TabConfig>,
    val tilesPerRow: Int = TILES_PER_ROW_AUTO,
    val viewerControls: List<ControlConfig> = defaultViewerControls(),
    val viewModes: Map<String, String> = emptyMap(),
    val gridColumns: Map<String, Int> = emptyMap(),
    val showTrashShortcut: Boolean = true,
) {
    companion object {
        const val CURRENT_VERSION = 1

        /** Порядок плиток по макету; «На ТВ» заменён на «Избранное». Ссылки и Файлы скрыты. */
        private val defaultVisibleTiles = listOf(
            SectionId.DOWNLOADS, SectionId.RECENT, SectionId.PHOTOS,
            SectionId.VIDEOS, SectionId.AUDIO, SectionId.DOCUMENTS, SectionId.ARCHIVES,
            SectionId.APPS, SectionId.FAVORITES, SectionId.NOTES, SectionId.TRANSFER,
        )

        val defaultTabs = listOf(
            SectionId.RECENT, SectionId.FILES, SectionId.APPS, SectionId.NOTES, SectionId.FAVORITES,
        )

        fun defaultTile(id: SectionId, visible: Boolean) =
            TileConfig(id.name, visible, id.defaultIcon, id.defaultColor)

        fun defaultTab(id: SectionId) = TabConfig(id.name, id.defaultIcon, id.defaultColor)

        fun defaults(): UiConfig {
            val visible = defaultVisibleTiles.map { defaultTile(it, true) }
            val hidden = SectionId.entries.filter { it !in defaultVisibleTiles }.map { defaultTile(it, false) }
            return UiConfig(tiles = visible + hidden, tabs = defaultTabs.map(::defaultTab))
        }

        fun defaultTabsConfig(): List<TabConfig> = defaultTabs.map(::defaultTab)

        fun defaultViewerControls(): List<ControlConfig> =
            ViewerControl.entries.map { ControlConfig(it.name, it.defaultVisible) }
    }
}

object UiConfigCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(config: UiConfig): String = json.encodeToString(UiConfig.serializer(), config)

    /** Повреждённые/чужие данные не роняют приложение: возвращаются значения по умолчанию. */
    fun decode(raw: String?): UiConfig {
        if (raw.isNullOrBlank()) return UiConfig.defaults()
        val parsed = runCatching { json.decodeFromString(UiConfig.serializer(), raw) }.getOrNull()
            ?: return UiConfig.defaults()
        return normalize(parsed)
    }

    /**
     * Приводит конфигурацию к допустимому виду, не меняя выбор пользователя:
     * неизвестные разделы/значки/цвета отбрасываются или заменяются умолчаниями,
     * дубликаты удаляются, новые разделы добавляются в конец скрытыми,
     * вкладок 1..5 (пустой список — умолчания, чтобы не потерять навигацию).
     */
    fun normalize(config: UiConfig): UiConfig {
        val seenTiles = mutableSetOf<SectionId>()
        val tiles = config.tiles.mapNotNull { tile ->
            val id = SectionId.fromKey(tile.id) ?: return@mapNotNull null
            if (!seenTiles.add(id)) return@mapNotNull null
            TileConfig(
                id = id.name,
                visible = tile.visible,
                icon = tile.icon.takeIf { it in IconKeys.all } ?: id.defaultIcon,
                color = tile.color.takeIf { it in ColorKeys.all } ?: id.defaultColor,
            )
        }
        val missing = SectionId.entries.filter { it !in seenTiles }.map { UiConfig.defaultTile(it, it == SectionId.TRANSFER || it == SectionId.AUDIO) }

        val seenTabs = mutableSetOf<SectionId>()
        val tabs = config.tabs.mapNotNull { tab ->
            val id = SectionId.fromKey(tab.id) ?: return@mapNotNull null
            if (!seenTabs.add(id)) return@mapNotNull null
            TabConfig(
                id = id.name,
                icon = tab.icon.takeIf { it in IconKeys.all } ?: id.defaultIcon,
                color = tab.color.takeIf { it in ColorKeys.all } ?: id.defaultColor,
            )
        }.take(MAX_TABS)

        val seenControls = mutableSetOf<ViewerControl>()
        val controls = config.viewerControls.mapNotNull { c ->
            val id = ViewerControl.fromKey(c.id) ?: return@mapNotNull null
            if (!seenControls.add(id)) return@mapNotNull null
            ControlConfig(id.name, c.visible)
        }
        val missingControls = ViewerControl.entries.filter { it !in seenControls }.map { ControlConfig(it.name, it.defaultVisible) }

        return UiConfig(
            version = UiConfig.CURRENT_VERSION,
            showTrashShortcut = config.showTrashShortcut,
            tiles = tiles + missing,
            tabs = tabs.ifEmpty { UiConfig.defaultTabsConfig() },
            tilesPerRow = config.tilesPerRow.takeIf { it in TILES_PER_ROW_OPTIONS } ?: TILES_PER_ROW_AUTO,
            viewerControls = controls + missingControls,
            viewModes = config.viewModes.filter { (k, v) ->
                k.length in 1..32 && FileViewMode.entries.any { it.name == v }
            },
            gridColumns = config.gridColumns.filter { (k, v) ->
                k in FileViewPrefs.columnKeys && v in FileViewPrefs.rangeForKey(k)
            },
        )
    }
}
