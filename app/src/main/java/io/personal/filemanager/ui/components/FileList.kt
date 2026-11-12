package io.personal.filemanager.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import io.personal.filemanager.ui.ops.LocalFileOps
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.sp
import io.personal.filemanager.core.FileKind
import io.personal.filemanager.core.gridStamp
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
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FileThumbnail(entry, 44.dp)
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
        Spacer(Modifier.width(10.dp))
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
    selection: SelectionState = remember { SelectionState() },
) {
    val navigator = LocalNavigator.current
    val files = remember(entries) { entries.filter { !it.isDirectory } }
    val open: (FileEntry) -> Unit = onOpen ?: { navigator.openFile(it, files) }
    // «Назад» в режиме выбора снимает выбор, а не уходит с экрана.
    BackHandler(enabled = selection.active) { selection.clear() }
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
                // Ненавязчивая граница вместо отдельных тяжёлых карточек для каждой строки.
                HorizontalDivider(
                    modifier = Modifier.padding(start = 70.dp),
                    thickness = 0.5.dp,
                    color = AppColors.Divider,
                )
            }
        }
        SelectionBar(
            selection = selection,
            entries = entries,
            modifier = Modifier.align(Alignment.BottomCenter),
            onAllActions = { menuFor = it },
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

/**
 * Сетка файлов с теми же действиями, что и список: [columns] файлов в ряду, под миниатюрой — имя, дата и время
 * («25.10 · 16:15»; в узкой ячейке — в две строки, часы и минуты не скрываются).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridPane(
    entries: List<FileEntry>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    dateShown: DateShown = DateShown.MODIFIED,
    galleryMode: Boolean = false,
    onOpen: ((FileEntry) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
    header: (LazyGridScope.() -> Unit)? = null,
    selection: SelectionState = remember { SelectionState() },
) {
    val navigator = LocalNavigator.current
    val files = remember(entries) { entries.filter { !it.isDirectory } }
    val open: (FileEntry) -> Unit = onOpen ?: { navigator.openFile(it, files) }
    var detailsFor by remember { mutableStateOf<FileEntry?>(null) }
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }
    BackHandler(enabled = selection.active) { selection.clear() }
    LaunchedEffect(entries) {
        val present = entries.mapTo(HashSet()) { it.path }
        if (selection.paths.any { it !in present }) selection.set(selection.paths.filter { it in present })
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val monthGroups = remember(entries, dateShown, galleryMode) {
            if (galleryMode) {
                entries.groupBy { entry ->
                    val timestamp = entry.stampTime(dateShown).first
                    if (timestamp != null && timestamp > 0L) {
                        java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.forLanguageTag("ru"))
                            .format(java.util.Date(timestamp))
                            .replaceFirstChar { it.titlecase(java.util.Locale.forLanguageTag("ru")) }
                    } else "Без даты"
                }
            } else emptyMap()
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (galleryMode) 2.dp else GRID_SIDE,
                end = if (galleryMode) 2.dp else GRID_SIDE,
                top = 4.dp,
                bottom = contentPadding.calculateBottomPadding() + if (selection.active) 140.dp else 0.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (galleryMode) 3.dp else GRID_GAP),
            verticalArrangement = Arrangement.spacedBy(if (galleryMode) 3.dp else GRID_GAP),
        ) {
            header?.invoke(this)
            if (galleryMode) {
                monthGroups.forEach { (month, groupEntries) ->
                    fullWidthItem {
                        Text(
                            month,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(groupEntries, key = { it.path }, contentType = { "gallery-media" }) { entry ->
                        GalleryMediaCell(
                            entry = entry,
                            selected = entry.path in selection.paths,
                            selectionMode = selection.active,
                            dateShown = dateShown,
                            onClick = { if (selection.active) selection.toggle(entry.path) else open(entry) },
                            onLongClick = { selection.toggle(entry.path) },
                        )
                    }
                }
            } else {
                items(entries, key = { it.path }, contentType = { "file" }) { entry ->
                    GridCell(
                        entry = entry,
                        selected = entry.path in selection.paths,
                        selectionMode = selection.active,
                        dateShown = dateShown,
                        onClick = { if (selection.active) selection.toggle(entry.path) else open(entry) },
                        onLongClick = { selection.toggle(entry.path) },
                    )
                }
            }
        }
        SelectionBar(selection, entries, Modifier.align(Alignment.BottomCenter), onAllActions = { menuFor = it })
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

// Almost edge-to-edge, matching a modern gallery; no bulky card margins.
private val GRID_SIDE = 2.dp
private val GRID_GAP = 3.dp
// Mixed grids (for example «Новые») must keep every tile on the same row baseline.
private val GRID_UNIFORM_FOOTER_HEIGHT = 62.dp
private val GRID_NAME_SLOT_HEIGHT = 16.dp
private val GRID_DATE_SLOT_HEIGHT = 24.dp
private val GRID_META_LAST_LINE_HEIGHT = 14.dp

/**
 * Compact metadata shown under every square thumbnail, never on top of it;
 * "Новые" uses MediaStore date-added when supplied, other sections use modification time.
 * Unknown dates are not substituted with the current time.
 */
@Composable
private fun FileGridMetadata(
    entry: FileEntry,
    dateShown: DateShown,
    includeName: Boolean,
    uniformHeight: Boolean = false,
) {
    val (epoch, fallback) = remember(entry, dateShown) { entry.stampTime(dateShown) }
    val stamp = remember(epoch) { gridStamp(epoch) }
    val date = remember(stamp, fallback) {
        stamp?.oneLine?.let { if (fallback) "изм. $it" else it } ?: "Дата неизвестна"
    }

    // This is a footer BELOW the thumbnail, not a scrim covering its lower edge.
    Column(
        modifier = Modifier.fillMaxWidth()
            .then(if (uniformHeight) Modifier.height(GRID_UNIFORM_FOOTER_HEIGHT) else Modifier)
            .background(AppColors.Card)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (uniformHeight) {
            // Reserve the filename line even for photo/video: the name stays hidden,
            // but mixed rows no longer become shorter than APK/document rows.
            Box(Modifier.fillMaxWidth().height(GRID_NAME_SLOT_HEIGHT)) {
                if (includeName) {
                    Text(
                        entry.name,
                        color = AppColors.TextPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(GRID_DATE_SLOT_HEIGHT)) {
                Text(
                    date,
                    color = AppColors.TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 2,
                    softWrap = true,
                    lineHeight = 12.sp,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.fillMaxWidth().height(GRID_META_LAST_LINE_HEIGHT)) {
                when {
                    !entry.isDirectory -> Text(
                        formatSize(entry.size),
                        color = AppColors.TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                    entry.childCount != null -> Text(
                        "${entry.childCount} объектов",
                        color = AppColors.TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        } else {
            if (includeName) {
                Text(
                    entry.name,
                    color = AppColors.TextPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                date,
                color = AppColors.TextSecondary,
                fontSize = 10.sp,
                maxLines = 2,
                softWrap = true,
                lineHeight = 12.sp,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDirectory) {
                Text(
                    formatSize(entry.size),
                    color = AppColors.TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            } else {
                entry.childCount?.let { count ->
                    Text(
                        "$count объектов",
                        color = AppColors.TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Square gallery thumbnails with date, time and size, but no filename captions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryMediaCell(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    dateShown: DateShown,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    uniformFooter: Boolean = false,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            GridThumbnail(entry)
            if (selected) {
                Box(Modifier.fillMaxSize().background(AppColors.Accent.copy(alpha = 0.25f)))
            }
            if (selectionMode) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (selected) "Выбрано" else "Выбрать",
                    tint = if (selected) AppColors.Accent else Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)),
                )
            }
        }
        FileGridMetadata(entry, dateShown, includeName = false, uniformHeight = uniformFooter)
    }
}

/** Время для подписи: в «Новых» по дате появления — дата добавления; если она неизвестна — честно «изм.». */
private fun FileEntry.stampTime(mode: DateShown): Pair<Long?, Boolean> =
    if (mode == DateShown.ADDED && dateAdded != null) dateAdded to false
    else if (mode == DateShown.ADDED) lastModified.takeIf { it > 0 } to true
    else lastModified.takeIf { it > 0 } to false

/**
 * Shared compact grid item. Preserve full-size square previews and add a slim
 * metadata footer outside the preview for ALL types, including APKs and folders.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    dateShown: DateShown,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    if (entry.kind == FileKind.IMAGE || entry.kind == FileKind.VIDEO) {
        GalleryMediaCell(
            entry = entry,
            selected = selected,
            selectionMode = selectionMode,
            dateShown = dateShown,
            onClick = onClick,
            onLongClick = onLongClick,
            uniformFooter = true,
        )
        return
    }

    val shape = RoundedCornerShape(7.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (selected) AppColors.AccentContainer else AppColors.Card)
            .then(if (selected) Modifier.border(2.dp, AppColors.Accent, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            GridThumbnail(entry)
            if (selectionMode) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (selected) "Выбрано" else "Выбрать",
                    tint = if (selected) AppColors.Accent else Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                        .background(Color.Black.copy(alpha = 0.48f), RoundedCornerShape(50)),
                )
            }
        }
        FileGridMetadata(entry, dateShown, includeName = true, uniformHeight = true)
    }
}

/** Действие панели выбора: значок с подписью, чтобы было видно, что делает кнопка. */
@Composable
private fun LabeledAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = AppColors.TextPrimary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextPrimary, maxLines = 1)
    }
}

fun LazyGridScope.fullWidthItem(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** Панель режима выбора: количество, «Выбрать все», действия. [onAllActions] — полное меню для одного файла. */
@Composable
fun SelectionBar(
    selection: SelectionState,
    entries: List<FileEntry>,
    modifier: Modifier = Modifier,
    onAllActions: ((FileEntry) -> Unit)? = null,
) {
    if (!selection.active) return
    val context = LocalContext.current
    val transferManager = io.personal.filemanager.ui.appContainer().transfer
    val transferNavigator = LocalNavigator.current
    val fileOps = LocalFileOps.current
    val chosen = remember(entries, selection.paths) { entries.filter { it.path in selection.paths } }
    var more by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .navigationBarsPadding()
            .card(RoundedCornerShape(20.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selection.clear() }) {
                Icon(Icons.Filled.Close, contentDescription = "Отменить выбор")
            }
            Text("Выбрано: ${chosen.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { selection.set(entries.map { it.path }) }) {
                Icon(Icons.Filled.SelectAll, contentDescription = "Выбрать все")
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            LabeledAction(Icons.Filled.Share, "Поделиться") { FileActions.share(context, chosen) }
            LabeledAction(Icons.Filled.ContentCopy, "Копировать") { fileOps.copy(chosen); selection.clear() }
            LabeledAction(Icons.AutoMirrored.Filled.DriveFileMove, "Переместить") { fileOps.move(chosen); selection.clear() }
            // Выбор не сбрасывается: при «Отмене» в диалоге он остаётся; удалённые файлы уходят из выбора сами.
            LabeledAction(Icons.Filled.Delete, "Удалить") { fileOps.delete(chosen) }
            Box {
                LabeledAction(Icons.Filled.MoreVert, "Ещё") { more = true }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("Передать") }, onClick = {
                        more = false; transferManager.share(chosen.map { java.io.File(it.path) }); transferNavigator.openSection(io.personal.filemanager.core.SectionId.TRANSFER)
                    })
                    if (chosen.size == 1 && onAllActions != null) {
                        DropdownMenuItem(
                            text = { Text("Все действия…") },
                            onClick = { more = false; val one = chosen.first(); selection.clear(); onAllActions(one) },
                        )
                    }
                    if (chosen.size == 1) {
                        DropdownMenuItem(
                            text = { Text("Переименовать") },
                            onClick = { more = false; fileOps.rename(chosen.first()); selection.clear() },
                        )
                    }
                    if (fileOps.supportsArchives) {
                        DropdownMenuItem(
                            text = { Text("Сжать в архив") },
                            onClick = { more = false; fileOps.compress(chosen); selection.clear() },
                        )
                    }
                }
            }
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
    val transferManager = io.personal.filemanager.ui.appContainer().transfer
    val transferNavigator = LocalNavigator.current
    val fileOps = LocalFileOps.current
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
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Открыть") { onDismiss(); onOpen() }
            SheetAction(Icons.Filled.Share, "Передать") {
                onDismiss(); transferManager.share(listOf(java.io.File(entry.path))); transferNavigator.openSection(io.personal.filemanager.core.SectionId.TRANSFER)
            }
            if (!entry.isDirectory) {
                SheetAction(Icons.Filled.Share, "Поделиться") { onDismiss(); FileActions.share(context, listOf(entry)) }
                if (entry.kind != FileKind.OTHER) {
                    SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Открыть в другом приложении") {
                        onDismiss(); FileActions.openExternally(context, entry)
                    }
                }
            }
            if (onSelect != null) {
                SheetAction(Icons.Filled.CheckCircle, "Выбрать") { onDismiss(); onSelect() }
            }
            if (fileOps.supportsArchives && entry.kind == FileKind.ARCHIVE) {
                SheetAction(Icons.Filled.Unarchive, "Распаковать…") { onDismiss(); fileOps.extract(entry) }
            }
            if (fileOps.supportsArchives) {
                SheetAction(Icons.Filled.Archive, "Сжать в архив") { onDismiss(); fileOps.compress(listOf(entry)) }
            }
            SheetAction(Icons.Filled.ContentCopy, "Копировать") { onDismiss(); fileOps.copy(listOf(entry)) }
            SheetAction(Icons.AutoMirrored.Filled.DriveFileMove, "Переместить") { onDismiss(); fileOps.move(listOf(entry)) }
            SheetAction(Icons.Filled.DriveFileRenameOutline, "Переименовать") { onDismiss(); fileOps.rename(entry) }
            if (fileOps.supportsFavorites) {
                val fav = fileOps.isFavorite(entry.path)
                SheetAction(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, if (fav) "Убрать из избранного" else "В избранное") {
                    onDismiss(); fileOps.toggleFavorite(entry)
                }
            }
            SheetAction(Icons.Filled.Delete, "Удалить") { onDismiss(); fileOps.delete(listOf(entry)) }
            SheetAction(Icons.Filled.Info, "Сведения") { onDismiss(); onDetails() }
            Spacer(Modifier.navigationBarsPadding().padding(bottom = 8.dp))
        }
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
            KindBadge(entry.kind, 48.dp)
            coil3.compose.AsyncImage(
                model = MediaThumb.of(entry),
                contentDescription = entry.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (entry.kind == FileKind.VIDEO) {
                Icon(
                    Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(32.dp),
                )
            }
        } else {
            // Папки, документы, архивы, APK (иконка приложения) — понятный значок типа.
            FileThumbnail(entry, 74.dp)
        }
    }
}
