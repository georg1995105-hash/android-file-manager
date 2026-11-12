package io.personal.filemanager.ui.home

import androidx.compose.material3.LocalContentColor
import io.personal.filemanager.core.TileLayout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.activity.compose.BackHandler
import io.personal.filemanager.ui.components.SelectionBar
import io.personal.filemanager.ui.components.SelectionState
import io.personal.filemanager.ui.components.tapClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.core.SectionId
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.core.usedPercent
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.settings.TileConfig
import io.personal.filemanager.data.settings.UiConfig
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.LocalCreateDialog
import io.personal.filemanager.ui.Routes
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.components.DateShown
import io.personal.filemanager.ui.components.FileActionsSheet
import io.personal.filemanager.ui.components.FileDetailsSheet
import io.personal.filemanager.ui.components.FileRow
import io.personal.filemanager.ui.components.RefreshWhileVisible
import io.personal.filemanager.ui.components.SectionHeader
import io.personal.filemanager.ui.components.SectionIcon
import io.personal.filemanager.ui.components.card
import io.personal.filemanager.ui.theme.AppColors

@Composable
fun HomeScreen() {
    val vm = appViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val config by appContainer().settings.uiConfig.collectAsStateWithLifecycle(initialValue = null)

    RefreshWhileVisible(vm.changes) { vm.refresh() }
    val opsVersion by appContainer().operations.version.collectAsStateWithLifecycle()
    LaunchedEffect(opsVersion) { if (opsVersion > 0) vm.refresh() }
    HomeContent(state, config)
}

/** Главная без источников данных — для экрана и для скриншот-тестов. */
@Composable
fun HomeContent(state: HomeState, config: UiConfig?) {
    val navigator = LocalNavigator.current
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }
    var detailsFor by remember { mutableStateOf<FileEntry?>(null) }
    val selection = remember { SelectionState() }
    BackHandler(enabled = selection.active) { selection.clear() }
    LaunchedEffect(state.recent) {
        val present = state.recent.mapTo(HashSet()) { it.path }
        if (selection.paths.any { it !in present }) selection.set(selection.paths.filter { it in present })
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val tiles = config?.tiles?.filter { it.visible }.orEmpty()
    // Колонки по ширине (телефон/планшет, вертикально/горизонтально) и настройке «Плиток в ряду».
    val columns = TileLayout.columns(config?.tilesPerRow ?: 0, maxWidth.value.toInt(), tiles.size)
    val compactHeight = maxHeight < 480.dp
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("home-content-list"),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item { HomeHeader() }
        item {
            StorageCard(
                state = state,
                onClick = { navigator.openSection(SectionId.FILES) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (!state.hasAccess) {
            item { AccessBanner(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
        }
        item {
            SectionHeader(
                "Разделы",
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 22.dp, bottom = 10.dp),
            ) {
                ConfigureButton(onClick = { navigator.openTileSettings() })
            }
        }
        items(tiles.chunked(columns), contentType = { "tiles" }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { tile ->
                    val id = SectionId.fromKey(tile.id) ?: return@forEach
                    SectionTile(
                        tile = tile,
                        title = id.title,
                        subtitle = state.stats[id],
                        onClick = { navigator.openSection(id) },
                        minHeight = if (compactHeight) 94.dp else 104.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (config != null && tiles.isEmpty()) {
            item {
                Text(
                    "Все разделы скрыты. Нажмите значок карандаша, чтобы вернуть нужные.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        item {
            SectionHeader(
                "Недавние",
                modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 2.dp),
            ) {
                TextButton(onClick = { navigator.openSection(SectionId.RECENT) }) {
                    Text("Все")
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
        if (state.hasAccess && state.recentLoaded && state.recent.isEmpty()) {
            item {
                Text(
                    "Недавно добавленных файлов пока нет.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        items(state.recent, key = { it.path }) { entry ->
            FileRow(
                entry = entry,
                dateShown = DateShown.ADDED,
                selected = entry.path in selection.paths,
                selectionMode = selection.active,
                onClick = { if (selection.active) selection.toggle(entry.path) else navigator.openFile(entry, state.recent) },
                onLongClick = { selection.toggle(entry.path) },
                onMenu = { menuFor = entry },
            )
        }
        if (selection.active) item { Spacer(Modifier.height(140.dp)) }
    }
    SelectionBar(
        selection = selection,
        entries = state.recent,
        modifier = Modifier.align(Alignment.BottomCenter),
        onAllActions = { menuFor = it },
    )
    }

    menuFor?.let { entry ->
        FileActionsSheet(
            entry = entry,
            onDismiss = { menuFor = null },
            onOpen = { navigator.openFile(entry, state.recent) },
            onSelect = null,
            onDetails = { detailsFor = entry },
        )
    }
    detailsFor?.let { FileDetailsSheet(it, onDismiss = { detailsFor = null }) }
}

@Composable
private fun HomeHeader() {
    val navigator = LocalNavigator.current
    val createFile = LocalCreateDialog.current
    val config by appContainer().settings.uiConfig.collectAsStateWithLifecycle(initialValue = null)
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Мои файлы", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { navigator.openSearch() }) {
            Icon(Icons.Filled.Search, contentDescription = "Поиск")
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Меню")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Создать файл или папку") },
                    onClick = { menuOpen = false; createFile() },
                )
                DropdownMenuItem(
                    text = { Text("Анализ памяти") },
                    onClick = { menuOpen = false; navigator.open(Routes.ANALYZER) },
                )
                DropdownMenuItem(
                    text = { Text("Настройки") },
                    onClick = { menuOpen = false; navigator.open(Routes.SETTINGS) },
                )
                if (config?.showTrashShortcut != false) {
                    DropdownMenuItem(
                        text = { Text("Корзина") },
                        onClick = { menuOpen = false; navigator.open(Routes.TRASH) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Резервные копии") },
                    onClick = { menuOpen = false; navigator.open(Routes.BACKUP) },
                )
            }
        }
    }
}

@Composable
private fun StorageCard(state: HomeState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val storage = state.storage
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .card(RoundedCornerShape(20.dp))
            .tapClickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(AppColors.Surface, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.SdStorage, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Внутренняя память", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AppColors.TextSecondary)
            }
            if (storage != null) {
                val percent = usedPercent(storage.usedBytes, storage.totalBytes)
                Text(
                    "${formatSize(storage.usedBytes)} из ${formatSize(storage.totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(50)),
                        color = AppColors.Accent,
                        trackColor = AppColors.CardBorder,
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("$percent%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("Сведения недоступны", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AccessBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxWidth()
            .card(RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text("Нужен доступ к файлам", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Без разрешения «Доступ ко всем файлам» приложение не видит документы, загрузки и архивы. " +
                "Это не root, а данные не покидают телефон.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { FileActions.openAllFilesAccessSettings(context) }) { Text("Разрешить") }
    }
}

@Composable
private fun ConfigureButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, AppColors.CardBorder, RoundedCornerShape(12.dp))
            .background(AppColors.Card)
            .tapClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = "Настроить разделы",
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SectionTile(
    tile: TileConfig,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 104.dp,
) {
    Column(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(18.dp))
            .card()
            .tapClickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SectionIcon(tile.icon, tile.color, 34.dp)
        Spacer(Modifier.height(6.dp))
        // Название в одну строку: в узкой плитке шрифт уменьшается, а не рвёт слово по слогам.
        val titleStyle = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = LocalContentColor.current,
        )
        BasicText(
            title,
            style = titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = titleStyle.fontSize, stepSize = 0.5.sp),
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}
