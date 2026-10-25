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
) {
    companion object {
        const val CURRENT_VERSION = 1

        /** Порядок плиток по макету; «На ТВ» заменён на «Избранное». Ссылки и Файлы скрыты. */
        private val defaultVisibleTiles = listOf(
            SectionId.DOWNLOADS, SectionId.RECENT, SectionId.PHOTOS,
            SectionId.VIDEOS, SectionId.DOCUMENTS, SectionId.ARCHIVES,
            SectionId.APPS, SectionId.FAVORITES, SectionId.NOTES,
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
        val missing = SectionId.entries.filter { it !in seenTiles }.map { UiConfig.defaultTile(it, false) }

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
            tiles = tiles + missing,
            tabs = tabs.ifEmpty { UiConfig.defaultTabsConfig() },
            tilesPerRow = config.tilesPerRow.takeIf { it in TILES_PER_ROW_OPTIONS } ?: TILES_PER_ROW_AUTO,
            viewerControls = controls + missingControls,
        )
    }
}
