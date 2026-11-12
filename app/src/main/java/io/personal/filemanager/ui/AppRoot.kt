package io.personal.filemanager.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.personal.filemanager.core.FileKind
import io.personal.filemanager.core.SectionId
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.MediaCategory
import io.personal.filemanager.data.settings.TabConfig
import io.personal.filemanager.ui.home.HomeScreen
import io.personal.filemanager.ui.components.QuickCreateDialog
import io.personal.filemanager.ui.icons.IconCatalog
import io.personal.filemanager.ui.sections.ApkScreen
import io.personal.filemanager.ui.sections.AppsScreen
import io.personal.filemanager.ui.sections.BrowserScreen
import io.personal.filemanager.ui.sections.MediaListScreen
import io.personal.filemanager.ui.sections.SearchScreen
import io.personal.filemanager.ui.sections.TrashScreen
import io.personal.filemanager.ui.ops.FileOpsHost
import io.personal.filemanager.ui.ops.FileOpsExtensions
import io.personal.filemanager.ui.personal.FavoritesScreen
import io.personal.filemanager.ui.personal.LinksScreen
import io.personal.filemanager.ui.personal.NoteEditorScreen
import io.personal.filemanager.ui.personal.NotesScreen
import io.personal.filemanager.ui.settings.BackupScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import io.personal.filemanager.ui.analyzer.AnalyzerScreen
import io.personal.filemanager.ui.vault.VaultScreen
import io.personal.filemanager.ui.archive.ArchiveScreen
import io.personal.filemanager.data.archive.ArchiveFormats
import io.personal.filemanager.ui.settings.TabSettingsScreen
import io.personal.filemanager.ui.settings.AppSettingsScreen
import io.personal.filemanager.ui.settings.TileSettingsScreen
import io.personal.filemanager.ui.theme.AccentColors
import io.personal.filemanager.ui.theme.AppColors
import io.personal.filemanager.ui.viewer.AudioPlayerScreen
import io.personal.filemanager.ui.viewer.MediaViewerScreen
import io.personal.filemanager.ui.viewer.TextViewerScreen
import io.personal.filemanager.ui.viewer.PdfViewerScreen
import io.personal.filemanager.ui.viewer.findActivity

@Composable
fun AppRoot(incomingPath: String? = null, onIncomingConsumed: () -> Unit = {}) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val container = appContainer()
    val config by container.settings.uiConfig.collectAsStateWithLifecycle(initialValue = null)
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val currentSection = when (route) {
        Routes.HOME -> SectionId.FILES
        Routes.SECTION -> backStack?.arguments?.getString("id")?.let(SectionId::fromKey)
        else -> null
    }
    val showBar = route == Routes.HOME || route == Routes.SECTION
    // «Создать» находится в меню ⋮ контекстных экранов, а не в глобальной FAB.
    var showCreateDialog by remember { mutableStateOf(false) }
    val openCreateDialog = { showCreateDialog = true }

    // Навигацию выполняем после снятия модального окна из композиции:
    // иначе NavBackStackEntry может остаться в INITIALIZED во время теста/ухода Activity.
    var pendingEditPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showCreateDialog, pendingEditPath) {
        val next = pendingEditPath ?: return@LaunchedEffect
        if (!showCreateDialog) {
            pendingEditPath = null
            nav.navigate(Routes.edit(next)) { launchSingleTop = true }
        }
    }

    val navigator = remember(nav) {
        object : AppNavigator {
            override fun openSection(id: SectionId) {
                nav.navigate(Routes.section(id)) { launchSingleTop = true }
            }

            override fun openFile(entry: FileEntry, siblings: List<FileEntry>) {
                when (entry.kind) {
                    FileKind.IMAGE, FileKind.VIDEO -> {
                        // Соседние фото и видео листаются в одном просмотре.
                        MediaViewerSession.paths = siblings.filter { it.kind == FileKind.IMAGE || it.kind == FileKind.VIDEO }
                            .map { it.path }
                            .ifEmpty { listOf(entry.path) }
                        nav.navigate(if (entry.kind == FileKind.IMAGE) Routes.image(entry.path) else Routes.video(entry.path))
                    }
                    FileKind.AUDIO -> {
                        AudioViewerSession.paths = siblings.filter { it.kind == FileKind.AUDIO }
                            .map { it.path }.ifEmpty { listOf(entry.path) }
                        nav.navigate(Routes.audio(entry.path))
                    }
                    FileKind.TEXT -> nav.navigate(Routes.text(entry.path))
                    FileKind.APK -> nav.navigate(Routes.apk(entry.path))
                    FileKind.PDF -> nav.navigate(Routes.pdf(entry.path))
                    FileKind.ARCHIVE -> if (ArchiveFormats.detect(entry.name) != null) {
                        nav.navigate(Routes.archive(entry.path))
                    } else {
                        FileActions.openExternally(context, entry)
                    }
                    // Папка из поиска/избранного — открываем в обзоре файлов.
                    FileKind.FOLDER -> nav.navigate(Routes.browser(entry.path))
                    else -> FileActions.openExternally(context, entry)
                }
            }

            override fun openSearch() {
                nav.navigate(Routes.SEARCH) { launchSingleTop = true }
            }

            override fun openTileSettings() {
                nav.navigate(Routes.SETTINGS_TILES) { launchSingleTop = true }
            }

            override fun openTabSettings() {
                nav.navigate(Routes.SETTINGS_TABS) { launchSingleTop = true }
            }

            override fun open(route: String) {
                nav.navigate(route) { launchSingleTop = true }
            }

            override fun back() {
                if (!nav.popBackStack()) context.findActivity()?.finish()
            }
        }
    }

    LaunchedEffect(incomingPath) {
        val path = incomingPath ?: return@LaunchedEffect
        onIncomingConsumed()
        navigator.openFile(FileEntry.from(File(path)))
    }

    CompositionLocalProvider(LocalNavigator provides navigator, LocalCreateDialog provides openCreateDialog) {
        io.personal.filemanager.ui.transfer.TransferApprovalDialog()
        FileOpsHost(bottomInset = if (showBar) 80.dp else 0.dp, extensions = rememberFavoritesExtension()) {
            Scaffold(
                containerColor = AppColors.Background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    val tabs = config?.tabs
                    if (showBar && tabs != null) {
                        BottomTabBar(
                            tabs = tabs, current = currentSection,
                            onSelect = { id -> selectTab(nav, id) },
                            onCreateRecent = openCreateDialog,
                        )
                    }
                },
            ) { inner ->
                NavHost(
                    navController = nav,
                    startDestination = Routes.HOME,
                    // Короткий cross-fade: переход остаётся быстрым, но не выглядит резким.
                    enterTransition = { fadeIn(animationSpec = tween(durationMillis = 120)) },
                    exitTransition = { fadeOut(animationSpec = tween(durationMillis = 80)) },
                    popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = 120)) },
                    popExitTransition = { fadeOut(animationSpec = tween(durationMillis = 80)) },
                    modifier = Modifier
                        .padding(inner)
                        .consumeWindowInsets(inner),
                ) {
                    composable(Routes.HOME) { HomeScreen() }
                    composable(
                        Routes.SECTION,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("id")?.let(SectionId::fromKey) ?: SectionId.FILES
                        SectionScreen(id)
                    }
                    composable(Routes.SEARCH) { SearchScreen() }
                    composable(
                        Routes.EDIT,
                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry -> TextViewerScreen(entry.arguments?.getString("path").orEmpty(), autoEdit = true) }
                    composable(
                        Routes.IMAGE,
                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry -> MediaViewerScreen(entry.arguments?.getString("path").orEmpty()) }
                    composable(
                        Routes.TEXT,
                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry -> TextViewerScreen(entry.arguments?.getString("path").orEmpty()) }
                    composable(
                        Routes.VIDEO,
                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry -> MediaViewerScreen(entry.arguments?.getString("path").orEmpty()) }
                    composable(
                        Routes.AUDIO,
                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry -> AudioPlayerScreen(entry.arguments?.getString("path").orEmpty()) }
                    composable(Routes.SETTINGS) { AppSettingsScreen() }
                    composable(Routes.SETTINGS_TILES) { TileSettingsScreen() }
                    composable(Routes.SETTINGS_TABS) { TabSettingsScreen() }
                    composable(Routes.TRASH) { TrashScreen() }
                    composable(
                        Routes.ARCHIVE,
                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry -> ArchiveScreen(entry.arguments?.getString("path").orEmpty()) }
                    composable(
                        Routes.NOTE,
                        arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L }),
                    ) { entry -> NoteEditorScreen(entry.arguments?.getLong("id") ?: 0L) }
                    composable(
                        Routes.BROWSER,
                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry ->
                        val path = entry.arguments?.getString("path").orEmpty()
                        BrowserScreen(title = File(path).name.ifEmpty { path }, rootPath = path)
                    }
                    composable(Routes.BACKUP) { BackupScreen() }
                    composable(Routes.ANALYZER) { AnalyzerScreen() }
                    composable(Routes.VAULT) { VaultScreen() }
                    composable(
                        Routes.PDF,
                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry -> PdfViewerScreen(entry.arguments?.getString("path").orEmpty()) }
                    composable(
                        Routes.APK,
                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry -> ApkScreen(entry.arguments?.getString("path").orEmpty()) }
                }
            }
            if (showCreateDialog) {
                QuickCreateDialog(
                    initialFolder = container.storage.downloadsPath,
                    rootFolder = container.storage.rootPath,
                    downloadsFolder = container.storage.downloadsPath,
                    onDismiss = { showCreateDialog = false },
                    onCreate = { folder, isFolder, name ->
                        withContext(Dispatchers.IO) {
                            if (isFolder) container.fileSystem.createFolder(folder, name)
                            else container.fileSystem.prepareFile(folder, name)
                        }
                    },
                    onCreated = { created ->
                        if (created.isDirectory) container.operations.notify("Папка создана")
                        else container.operations.notify("Файл будет создан после сохранения текста")
                        if (!created.isDirectory) pendingEditPath = created.path
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionScreen(id: SectionId) {
    val storage = appContainer().storage
    when (id) {
        SectionId.TRANSFER -> io.personal.filemanager.ui.transfer.TransferScreen()
        SectionId.FILES -> BrowserScreen(title = "Внутренняя память", rootPath = storage.rootPath)
        SectionId.DOWNLOADS -> BrowserScreen(title = SectionId.DOWNLOADS.title, rootPath = storage.downloadsPath, viewKey = "DOWNLOADS")
        SectionId.RECENT -> MediaListScreen(id, MediaCategory.RECENT)
        SectionId.PHOTOS -> MediaListScreen(id, MediaCategory.PHOTOS)
        SectionId.VIDEOS -> MediaListScreen(id, MediaCategory.VIDEOS)
        SectionId.AUDIO -> MediaListScreen(id, MediaCategory.AUDIO)
        SectionId.DOCUMENTS -> MediaListScreen(id, MediaCategory.DOCUMENTS)
        SectionId.ARCHIVES -> MediaListScreen(id, MediaCategory.ARCHIVES)
        SectionId.APPS -> AppsScreen()
        SectionId.NOTES -> NotesScreen()
        SectionId.LINKS -> LinksScreen()
        SectionId.FAVORITES -> FavoritesScreen()
    }
}

/** Вкладка «Файлы» — главный экран «Мои файлы»; остальные — разделы. Назад с вкладки ведёт на главную. */
private fun selectTab(nav: NavHostController, id: SectionId) {
    if (id == SectionId.FILES) {
        if (nav.currentDestination?.route != Routes.HOME) nav.popBackStack(Routes.HOME, inclusive = false)
        return
    }
    if (nav.currentDestination?.route == Routes.SECTION &&
        nav.currentBackStackEntry?.arguments?.getString("id") == id.name) return
    nav.navigate(Routes.section(id)) {
        popUpTo(Routes.HOME)
        launchSingleTop = true
    }
}

/** Compact navigation with a hard-bounded row and Android system-bar spacing. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BottomTabBar(
    tabs: List<TabConfig>,
    current: SectionId?,
    onSelect: (SectionId) -> Unit = {},
    onCreateRecent: () -> Unit = {},
) {
    // Do not use weighted Columns as children of Material3 NavigationBar:
    // its internal measurement may allow them to occupy the entire Scaffold.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(thickness = 1.dp, color = AppColors.CardBorder.copy(alpha = 0.6f))
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tabs.forEach { tab ->
                val id = SectionId.fromKey(tab.id) ?: return@forEach
                val selected = id == current
                val color = if (selected) AccentColors.of(tab.color) else AppColors.TextSecondary
                var createMenu by remember(id) { mutableStateOf(false) }

                Box(modifier = Modifier.weight(1f).height(70.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("bottom-tab-${id.name}")
                            .clip(RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = { onSelect(id) },
                                onLongClick = if (id == SectionId.RECENT) {
                                    { createMenu = true }
                                } else null,
                                onLongClickLabel = if (id == SectionId.RECENT) "Создать файл или папку" else null,
                            )
                            .semantics { role = Role.Tab; this.selected = selected },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(58.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(17.dp))
                                .background(if (selected) color.copy(alpha = 0.17f) else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                IconCatalog.of(tab.icon),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = color,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            id.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (id == SectionId.RECENT) {
                        DropdownMenu(
                            expanded = createMenu,
                            onDismissRequest = { createMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Создать файл или папку") },
                                onClick = {
                                    createMenu = false
                                    onCreateRecent()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Избранное в меню действий файла: отметка хранится в локальной базе (путь, без копии файла). */
@Composable
private fun rememberFavoritesExtension(): FileOpsExtensions {
    val container = appContainer()
    val paths by remember { container.personal.favorites.map { list -> list.map { it.path }.toSet() } }
        .collectAsStateWithLifecycle(initialValue = emptySet())
    return remember(paths) {
        object : FileOpsExtensions {
            override val supportsFavorites = true
            override fun isFavorite(path: String) = path in paths
            override fun toggleFavorite(entry: FileEntry) {
                container.appScope.launch {
                    val added = container.personal.toggleFavorite(entry.path, entry.name, entry.isDirectory)
                    container.operations.notify(if (added) "Добавлено в избранное" else "Убрано из избранного")
                }
            }
        }
    }
}
