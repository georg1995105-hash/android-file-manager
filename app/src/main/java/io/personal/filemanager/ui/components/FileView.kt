package io.personal.filemanager.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.settings.FileViewMode
import io.personal.filemanager.data.settings.FileViewPrefs
import io.personal.filemanager.data.settings.gridColumns
import io.personal.filemanager.data.settings.viewMode
import io.personal.filemanager.ui.appContainer
import kotlinx.coroutines.launch

/** Вид файлового раздела: режим и число файлов в ряду для текущего устройства и положения экрана. */
@Stable
class FileViewState(
    val mode: FileViewMode,
    val columns: Int,
    val range: IntRange,
    val tablet: Boolean,
    val landscape: Boolean,
    val setMode: (FileViewMode) -> Unit,
    val setColumns: (Int) -> Unit,
)

/**
 * Настройка вида раздела [section] («RECENT», «FILES», «PHOTOS», «SEARCH»…). Хранится в настройках интерфейса
 * (входит в резервную копию); колонки — отдельно для телефона/планшета и вертикали/горизонтали.
 */
@Composable
fun rememberFileView(section: String): FileViewState {
    val container = appContainer()
    val config by container.settings.uiConfig.collectAsStateWithLifecycle(initialValue = null)
    val configuration = LocalConfiguration.current
    val tablet = configuration.smallestScreenWidthDp >= 600
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val c = config
    val mode = c?.viewMode(section) ?: FileViewPrefs.defaultMode(section)
    val columns = c?.gridColumns(tablet, landscape) ?: FileViewPrefs.defaultColumns(tablet, landscape)
    return remember(section, mode, columns, tablet, landscape) {
        FileViewState(
            mode = mode,
            columns = columns,
            range = FileViewPrefs.range(tablet),
            tablet = tablet,
            landscape = landscape,
            setMode = { m -> scope.launch { container.settings.updateUiConfig { it.copy(viewModes = it.viewModes + (section to m.name)) } } },
            setColumns = { n ->
                val key = FileViewPrefs.columnsKey(tablet, landscape)
                scope.launch { container.settings.updateUiConfig { it.copy(gridColumns = it.gridColumns + (key to n)) } }
            },
        )
    }
}

/** Кнопка в заголовке раздела: «Список / Сетка» и число файлов в ряду. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViewModeButton(view: FileViewState) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                if (view.mode == FileViewMode.GRID) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                contentDescription = "Вид: " + if (view.mode == FileViewMode.GRID) "сетка" else "список",
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text((if (view.mode == FileViewMode.LIST) "✓ " else "") + "Список") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null) },
                onClick = { open = false; view.setMode(FileViewMode.LIST) },
            )
            DropdownMenuItem(
                text = { Text((if (view.mode == FileViewMode.GRID) "✓ " else "") + "Сетка") },
                leadingIcon = { Icon(Icons.Filled.GridView, contentDescription = null) },
                onClick = { open = false; view.setMode(FileViewMode.GRID) },
            )
            HorizontalDivider()
            Text(
                "Файлов в ряду (" + (if (view.tablet) "планшет" else "телефон") + ", " +
                    (if (view.landscape) "горизонтально" else "вертикально") + ")",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 260.dp),
            )
            FlowRow(
                Modifier.padding(horizontal = 12.dp).widthIn(max = 280.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                view.range.forEach { n ->
                    FilterChip(
                        selected = view.mode == FileViewMode.GRID && n == view.columns,
                        onClick = {
                            open = false
                            view.setColumns(n)
                            view.setMode(FileViewMode.GRID)
                        },
                        label = { Text(n.toString()) },
                    )
                }
            }
        }
    }
}

/**
 * Файлы раздела списком или сеткой. Выбор (долгое нажатие) общий для обоих видов и переживает переключение.
 * [headerText] — пояснение над файлами (например, о сортировке «Новых»).
 */
@Composable
fun FilesPane(
    entries: List<FileEntry>,
    view: FileViewState,
    dateShown: DateShown,
    modifier: Modifier = Modifier,
    onOpen: ((FileEntry) -> Unit)? = null,
    headerText: String? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
) {
    val selection = remember { SelectionState() }
    val headerStyle = MaterialTheme.typography.bodySmall
    val headerColor = MaterialTheme.colorScheme.onSurfaceVariant
    if (view.mode == FileViewMode.GRID) {
        FileGridPane(
            entries = entries,
            modifier = modifier,
            columns = view.columns,
            dateShown = dateShown,
            onOpen = onOpen,
            contentPadding = contentPadding,
            header = headerText?.let { text ->
                { fullWidthItem { Text(text, style = headerStyle, color = headerColor, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) } }
            },
            selection = selection,
        )
    } else {
        FileListPane(
            entries = entries,
            dateShown = dateShown,
            modifier = modifier,
            onOpen = onOpen,
            contentPadding = contentPadding,
            header = headerText?.let { text ->
                { item { Text(text, style = headerStyle, color = headerColor, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) } }
            },
            selection = selection,
        )
    }
}
