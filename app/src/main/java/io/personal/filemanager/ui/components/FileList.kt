package io.personal.filemanager.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.personal.filemanager.core.FileKind
import io.personal.filemanager.core.formatFullDate
import io.personal.filemanager.core.formatShortDate
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.core.pluralRu
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.theme.AppColors

/** Множественный выбор по пути файла. */
@Stable
class SelectionState {
    var paths by mutableStateOf<Set<String>>(emptySet())
        private set

    val active: Boolean get() = paths.isNotEmpty()

    fun toggle(path: String) {
        paths = if (path in paths) paths - path else paths + path
    }

    fun set(all: Collection<String>) {
        paths = all.toSet()
    }

    fun clear() {
        paths = emptySet()
    }
}

/** Какая дата показывается в строке файла. */
enum class DateShown { MODIFIED, ADDED }

fun FileEntry.shownDate(mode: DateShown): Long =
    if (mode == DateShown.ADDED) dateAdded ?: lastModified else lastModified

fun FileEntry.subtitle(mode: DateShown): String = if (isDirectory) {
    val count = childCount
    val items = if (count != null) pluralRu(count, "объект", "объекта", "объектов") else "папка"
    "${formatShortDate(lastModified)} • $items"
} else {
    "${formatShortDate(shownDate(mode))} • ${formatSize(size)}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    entry: FileEntry,
    dateShown: DateShown,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) AppColors.AccentContainer.copy(alpha = 0.55f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FileThumbnail(entry, 52.dp)
            if (selectionMode) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (selected) "Выбрано" else "Не выбрано",
                    tint = if (selected) AppColors.Accent else Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .background(AppColors.Background, RoundedCornerShape(50)),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.subtitle(dateShown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onMenu, enabled = !selectionMode) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Действия с «${entry.name}»")
        }
    }
}

/**
 * Полноценный список файлов: открытие, долгое нажатие — выбор, меню действий, сведения,
 * панель выбора внизу (зона большого пальца) с «Поделиться».
 */
@Composable
fun FileListPane(
    entries: List<FileEntry>,
    dateShown: DateShown,
    modifier: Modifier = Modifier,
    onOpen: ((FileEntry) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
    header: (LazyListScope.() -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    val selection = remember { SelectionState() }
    val files = entries.filter { !it.isDirectory }
    val open: (FileEntry) -> Unit = onOpen ?: { navigator.openFile(it, files) }
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }
    var detailsFor by remember { mutableStateOf<FileEntry?>(null) }

    // Выбранные, но исчезнувшие из списка файлы снимаются с выбора.
    LaunchedEffect(entries) {
        val present = entries.mapTo(HashSet()) { it.path }
        if (selection.paths.any { it !in present }) selection.set(selection.paths.filter { it in present })
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
            header?.invoke(this)
            items(entries, key = { it.path }) { entry ->
                FileRow(
                    entry = entry,
                    dateShown = dateShown,
                    selected = entry.path in selection.paths,
                    selectionMode = selection.active,
                    onClick = { if (selection.active) selection.toggle(entry.path) else open(entry) },
                    onLongClick = { selection.toggle(entry.path) },
                    onMenu = { menuFor = entry },
                )
            }
        }
        SelectionBar(
            selection = selection,
            entries = entries,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    menuFor?.let { entry ->
        FileActionsSheet(
            entry = entry,
            onDismiss = { menuFor = null },
            onOpen = { open(entry) },
            onSelect = { selection.toggle(entry.path) },
            onDetails = { detailsFor = entry },
        )
    }
    detailsFor?.let { entry -> FileDetailsSheet(entry, onDismiss = { detailsFor = null }) }
}

/** Сетка миниатюр (Фото/Видео) с теми же действиями, что и список. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridPane(
    entries: List<FileEntry>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    val selection = remember { SelectionState() }
    var detailsFor by remember { mutableStateOf<FileEntry?>(null) }
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                bottom = contentPadding.calculateBottomPadding(),
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            header?.invoke(this)
            items(entries, key = { it.path }) { entry ->
                val selected = entry.path in selection.paths
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .then(if (selected) Modifier.border(3.dp, AppColors.Accent, RoundedCornerShape(10.dp)) else Modifier)
                        .combinedClickable(
                            onClick = {
                                if (selection.active) selection.toggle(entry.path) else navigator.openFile(entry, entries)
                            },
                            onLongClick = { if (selection.active) selection.toggle(entry.path) else menuFor = entry },
                        ),
                ) {
                    GridThumbnail(entry)
                    if (selection.active) {
                        Icon(
                            if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                            contentDescription = null,
                            tint = if (selected) AppColors.Accent else Color.White,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(22.dp),
                        )
                    }
                }
            }
        }
        SelectionBar(selection, entries, Modifier.align(Alignment.BottomCenter))
    }
    menuFor?.let { entry ->
        FileActionsSheet(
            entry = entry,
            onDismiss = { menuFor = null },
            onOpen = { navigator.openFile(entry, entries) },
            onSelect = { selection.toggle(entry.path) },
            onDetails = { detailsFor = entry },
        )
    }
    detailsFor?.let { entry -> FileDetailsSheet(entry, onDismiss = { detailsFor = null }) }
}

fun LazyGridScope.fullWidthItem(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SelectionBar(selection: SelectionState, entries: List<FileEntry>, modifier: Modifier = Modifier) {
    if (!selection.active) return
    val context = LocalContext.current
    val chosen = entries.filter { it.path in selection.paths }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .navigationBarsPadding()
            .card(RoundedCornerShape(20.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { selection.clear() }) {
            Icon(Icons.Filled.Close, contentDescription = "Отменить выбор")
        }
        Text("Выбрано: ${chosen.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { selection.set(entries.filter { !it.isDirectory }.map { it.path }) }) {
            Icon(Icons.Filled.SelectAll, contentDescription = "Выбрать все файлы")
        }
        TextButton(onClick = { FileActions.share(context, chosen) }) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Поделиться")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionsSheet(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onSelect: (() -> Unit)?,
    onDetails: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.Surface) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileThumbnail(entry, 44.dp)
            Spacer(Modifier.width(12.dp))
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = AppColors.Divider)
        SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Открыть") { onDismiss(); onOpen() }
        if (!entry.isDirectory) {
            SheetAction(Icons.Filled.Share, "Поделиться") { onDismiss(); FileActions.share(context, listOf(entry)) }
            if (entry.kind != FileKind.OTHER) {
                SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Открыть в другом приложении") {
                    onDismiss(); FileActions.openExternally(context, entry)
                }
            }
            if (onSelect != null) {
                SheetAction(Icons.Filled.CheckCircle, "Выбрать") { onDismiss(); onSelect() }
            }
        }
        SheetAction(Icons.Filled.Info, "Сведения") { onDismiss(); onDetails() }
        Spacer(Modifier.navigationBarsPadding().padding(bottom = 8.dp))
    }
}

@Composable
private fun SheetAction(icon: ImageVector, text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailsSheet(entry: FileEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = remember(entry.path) { FileActions.contentUri(context, entry.path)?.toString() ?: "недоступен" }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.Surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("Сведения", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(12.dp))
            SelectionContainer {
                Column {
                    DetailLine("Имя", entry.name)
                    DetailLine("Путь", entry.path)
                    if (entry.isDirectory) {
                        DetailLine("Тип", "Папка")
                        entry.childCount?.let { DetailLine("Содержимое", pluralRu(it, "объект", "объекта", "объектов")) }
                    } else {
                        DetailLine("Размер", "${formatSize(entry.size)} (${entry.size} байт)")
                        DetailLine("Тип", FileActions.mimeOf(entry))
                        DetailLine("URI для отправки", uri)
                    }
                    DetailLine("Изменён", formatFullDate(entry.lastModified))
                    entry.dateAdded?.let { DetailLine("Добавлен в медиатеку Android", formatFullDate(it)) }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GridThumbnail(entry: FileEntry) {
    Box(Modifier.fillMaxSize().background(AppColors.Card), contentAlignment = Alignment.Center) {
        if (entry.kind == FileKind.IMAGE || entry.kind == FileKind.VIDEO) {
            coil3.compose.SubcomposeAsyncImage(
                model = java.io.File(entry.path),
                contentDescription = entry.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = { KindBadge(entry.kind, 48.dp) },
            )
            if (entry.kind == FileKind.VIDEO) {
                Icon(
                    Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(36.dp),
                )
            }
        } else {
            KindBadge(entry.kind, 48.dp)
        }
    }
}
