package io.personal.filemanager.ui.settings

import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import io.personal.filemanager.data.settings.TILES_PER_ROW_OPTIONS
import io.personal.filemanager.data.settings.TILES_PER_ROW_AUTO
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.AppContainer
import io.personal.filemanager.core.SectionId
import io.personal.filemanager.data.settings.TileConfig
import io.personal.filemanager.data.settings.UiConfig
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.components.IconChoice
import io.personal.filemanager.ui.components.IconColorPickerSheet
import io.personal.filemanager.ui.components.LoadingBox
import io.personal.filemanager.ui.components.Pill
import io.personal.filemanager.ui.components.ReorderableColumn
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.components.SectionIcon
import io.personal.filemanager.ui.components.card
import io.personal.filemanager.ui.components.moved
import io.personal.filemanager.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Плитки главной. Локальная копия списка — источник истины на экране (плавное перетаскивание),
 * каждое изменение сразу сохраняется в DataStore.
 */
class TileSettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val _tiles = MutableStateFlow<List<TileConfig>?>(null)
    val tiles: StateFlow<List<TileConfig>?> = _tiles.asStateFlow()
    val showTrashShortcut: StateFlow<Boolean> = container.settings.uiConfig.map { it.showTrashShortcut }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setTrashShortcut(enabled: Boolean) {
        viewModelScope.launch { container.settings.updateUiConfig { it.copy(showTrashShortcut = enabled) } }
    }
    val tilesPerRow: StateFlow<Int> = container.settings.uiConfig.map { it.tilesPerRow }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TILES_PER_ROW_AUTO)

    init {
        viewModelScope.launch { _tiles.value = container.settings.uiConfig.first().tiles }
    }

    fun setTilesPerRow(value: Int) {
        viewModelScope.launch { container.settings.updateUiConfig { it.copy(tilesPerRow = value) } }
    }

    private fun set(list: List<TileConfig>) {
        _tiles.value = list
        viewModelScope.launch { container.settings.updateUiConfig { it.copy(tiles = list) } }
    }

    fun move(from: Int, to: Int) {
        _tiles.value?.let { set(it.moved(from, to)) }
    }

    fun update(id: String, transform: (TileConfig) -> TileConfig) {
        _tiles.value?.let { list -> set(list.map { if (it.id == id) transform(it) else it }) }
    }

    fun reset() = set(UiConfig.defaults().tiles)
}

@Composable
fun TileSettingsScreen() {
    val vm = appViewModel { TileSettingsViewModel(it) }
    val tiles by vm.tiles.collectAsStateWithLifecycle()
    val perRow by vm.tilesPerRow.collectAsStateWithLifecycle()
    val showTrashShortcut by vm.showTrashShortcut.collectAsStateWithLifecycle()
    TileSettingsContent(
        tiles,
        onMove = vm::move,
        onUpdate = vm::update,
        onReset = vm::reset,
        tilesPerRow = perRow,
        onTilesPerRow = vm::setTilesPerRow,
        showTrashShortcut = showTrashShortcut,
        onTrashShortcut = vm::setTrashShortcut,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TileSettingsContent(
    tiles: List<TileConfig>?,
    onMove: (Int, Int) -> Unit,
    onUpdate: (String, (TileConfig) -> TileConfig) -> Unit,
    onReset: () -> Unit,
    tilesPerRow: Int = TILES_PER_ROW_AUTO,
    onTilesPerRow: ((Int) -> Unit)? = null,
    showTrashShortcut: Boolean = true,
    onTrashShortcut: ((Boolean) -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    var editing by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "Иконки разделов",
            onBack = { navigator.back() },
            actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Меню") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Сбросить всё") }, onClick = { menu = false; confirmReset = true })
                    }
                }
            },
        )
        val list = tiles
        if (list == null) {
            LoadingBox()
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                "Выберите значки для разделов на главном экране. Можно использовать предложенные иконки " +
                    "или другие из набора, менять цвет, порядок (перетащите за ⠿) и скрывать разделы.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (onTilesPerRow != null) {
                Text(
                    "Плиток в ряду",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                )
                FlowRow(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TILES_PER_ROW_OPTIONS.forEach { n ->
                        FilterChip(
                            selected = n == tilesPerRow,
                            onClick = { onTilesPerRow(n) },
                            label = { Text(if (n == TILES_PER_ROW_AUTO) "Авто" else n.toString()) },
                        )
                    }
                }
                Text(
                    "«Авто» подбирает число по ширине экрана: 3 на телефоне вертикально, больше — горизонтально и на " +
                        "планшете. Если плитки стали бы слишком узкими, их в ряду будет меньше.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            if (onTrashShortcut != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Показывать корзину в меню ⋮", style = MaterialTheme.typography.titleSmall)
                        Text("Скрывает пункт меню, но не удаляет содержимое корзины и не отключает безопасное удаление.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = showTrashShortcut, onCheckedChange = onTrashShortcut)
                }
            }
            Spacer(Modifier.height(8.dp))
            ReorderableColumn(
                items = list,
                keyOf = { it.id },
                onMove = onMove,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { tile, _, handle, dragging ->
                val id = SectionId.fromKey(tile.id) ?: return@ReorderableColumn
                TileCard(
                    tile = tile,
                    title = id.title,
                    suggested = id.suggestedIcons,
                    handle = handle,
                    dragging = dragging,
                    onPickIcon = { icon -> onUpdate(tile.id) { it.copy(icon = icon) } },
                    onToggleVisible = { onUpdate(tile.id) { it.copy(visible = !it.visible) } },
                    onChange = { editing = tile.id },
                )
            }
        }
    }

    val editingTile = tiles?.firstOrNull { it.id == editing }
    if (editingTile != null) {
        IconColorPickerSheet(
            title = SectionId.fromKey(editingTile.id)?.title.orEmpty(),
            iconKey = editingTile.icon,
            colorKey = editingTile.color,
            onChange = { icon, color -> onUpdate(editingTile.id) { it.copy(icon = icon, color = color) } },
            onDismiss = { editing = null },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить разделы?") },
            text = { Text("Порядок, значки, цвета и видимость плиток вернутся к исходным. Нижняя панель не изменится.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Сбросить") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
}

@Composable
private fun TileCard(
    tile: TileConfig,
    title: String,
    suggested: List<String>,
    handle: Modifier,
    dragging: Boolean,
    onPickIcon: (String) -> Unit,
    onToggleVisible: () -> Unit,
    onChange: () -> Unit,
) {
    val choices = if (tile.icon in suggested) suggested else listOf(tile.icon) + suggested.take(4)
    Column(
        Modifier
            .padding(vertical = 5.dp)
            .fillMaxWidth()
            .card(RoundedCornerShape(20.dp))
            .then(if (dragging) Modifier.alpha(0.95f) else Modifier)
            .padding(top = 10.dp, bottom = 12.dp, start = 4.dp, end = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(handle.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.DragIndicator, contentDescription = "Перетащить «$title»", tint = AppColors.TextSecondary)
            }
            SectionIcon(tile.icon, tile.color, 44.dp, Modifier.alpha(if (tile.visible) 1f else 0.4f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (tile.visible) "На главной" else "Скрыт",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleVisible) {
                Icon(
                    if (tile.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (tile.visible) "Скрыть «$title»" else "Показать «$title»",
                    tint = if (tile.visible) AppColors.TextPrimary else AppColors.TextSecondary,
                )
            }
            Pill(
                "Сменить",
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onChange),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            choices.forEach { icon ->
                IconChoice(icon, tile.color, selected = icon == tile.icon, onClick = { onPickIcon(icon) }, size = 52.dp)
            }
        }
    }
}
