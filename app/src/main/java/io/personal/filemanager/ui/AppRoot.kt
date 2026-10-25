package io.personal.filemanager.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import io.personal.filemanager.ui.icons.IconCatalog
import io.personal.filemanager.ui.sections.AppsScreen
import io.personal.filemanager.ui.sections.BrowserScreen
import io.personal.filemanager.ui.sections.MediaListScreen
import io.personal.filemanager.ui.sections.PlannedSectionScreen
import io.personal.filemanager.ui.sections.SearchScreen
import io.personal.filemanager.ui.sections.TrashScreen
import io.personal.filemanager.ui.ops.FileOpsHost
import io.personal.filemanager.ui.settings.TabSettingsScreen
import io.personal.filemanager.ui.settings.TileSettingsScreen
import io.personal.filemanager.ui.theme.AccentColors
import io.personal.filemanager.ui.theme.AppColors
import io.personal.filemanager.ui.viewer.ImageViewerScreen
import io.personal.filemanager.ui.viewer.TextViewerScreen
import io.personal.filemanager.ui.viewer.VideoPlayerScreen
import io.personal.filemanager.ui.viewer.findActivity

@Composable
fun AppRoot() {
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

    val navigator = remember(nav) {
        object : AppNavigator {
            override fun openSection(id: SectionId) {
                nav.navigate(Routes.section(id)) { launchSingleTop = true }
            }

            override fun openFile(entry: FileEntry, siblings: List<FileEntry>) {
                when (entry.kind) {
                    FileKind.IMAGE -> {
                        ImageViewerSession.paths = siblings.filter { it.kind == FileKind.IMAGE }.map { it.path }
                            .ifEmpty { listOf(entry.path) }
                        nav.navigate(Routes.image(entry.path))
                    }
                    FileKind.TEXT -> nav.navigate(Routes.text(entry.path))
                    FileKind.VIDEO -> nav.navigate(Routes.video(entry.path))
                    // Папка из поиска/недавних — открываем в обзоре файлов.
                    FileKind.FOLDER -> FileActions.toast(context, "Откройте папку через «Файлы»: ${entry.path}")
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

    CompositionLocalProvider(LocalNavigator provides navigator) {
        FileOpsHost(bottomInset = if (showBar) 80.dp else 0.dp) {
            Scaffold(
                containerColor = AppColors.Background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    val tabs = config?.tabs
                    if (showBar && tabs != null) {
                        BottomTabBar(tabs = tabs, current = currentSection, onSelect = { id -> selectTab(nav, id) })
                    }
                },
            ) { inner ->
                NavHost(
                    navController = nav,
                    startDestination = Routes.HOME,
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
                        Routes.IMAGE,
                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry -> ImageViewerScreen(entry.arguments?.getString("path").orEmpty()) }
                    composable(
                        Routes.TEXT,
                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry -> TextViewerScreen(entry.arguments?.getString("path").orEmpty()) }
                    composable(
                        Routes.VIDEO,
                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry -> VideoPlayerScreen(entry.arguments?.getString("path").orEmpty()) }
                    composable(Routes.SETTINGS_TILES) { TileSettingsScreen() }
                    composable(Routes.SETTINGS_TABS) { TabSettingsScreen() }
                    composable(Routes.TRASH) { TrashScreen() }
                }
            }
        }
    }
}

@Composable
private fun SectionScreen(id: SectionId) {
    val storage = appContainer().storage
    when (id) {
        SectionId.FILES -> BrowserScreen(title = "Внутренняя память", rootPath = storage.rootPath)
        SectionId.DOWNLOADS -> BrowserScreen(title = SectionId.DOWNLOADS.title, rootPath = storage.downloadsPath)
        SectionId.RECENT -> MediaListScreen(id, MediaCategory.RECENT)
        SectionId.PHOTOS -> MediaListScreen(id, MediaCategory.PHOTOS)
        SectionId.VIDEOS -> MediaListScreen(id, MediaCategory.VIDEOS)
        SectionId.DOCUMENTS -> MediaListScreen(id, MediaCategory.DOCUMENTS)
        SectionId.ARCHIVES -> MediaListScreen(id, MediaCategory.ARCHIVES)
        SectionId.APPS -> AppsScreen()
        SectionId.NOTES, SectionId.LINKS, SectionId.FAVORITES -> PlannedSectionScreen(id)
    }
}

/** Вкладка «Файлы» — главный экран «Мои файлы»; остальные — разделы. Назад с вкладки ведёт на главную. */
private fun selectTab(nav: NavHostController, id: SectionId) {
    if (id == SectionId.FILES) {
        nav.popBackStack(Routes.HOME, inclusive = false)
        return
    }
    nav.navigate(Routes.section(id)) {
        popUpTo(Routes.HOME)
        launchSingleTop = true
    }
}

@Composable
internal fun BottomTabBar(tabs: List<TabConfig>, current: SectionId?, onSelect: (SectionId) -> Unit) {
    NavigationBar(containerColor = AppColors.Surface, tonalElevation = 0.dp) {
        tabs.forEach { tab ->
            val id = SectionId.fromKey(tab.id) ?: return@forEach
            val selected = id == current
            val color = if (selected) AccentColors.of(tab.color) else AppColors.TextSecondary
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(id) },
                icon = { Icon(IconCatalog.of(tab.icon), contentDescription = null, tint = color) },
                label = {
                    Text(
                        id.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                },
                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
            )
        }
    }
}
