package io.personal.filemanager.ui.sections

import io.personal.filemanager.ui.appSharedViewModel
import io.personal.filemanager.ui.components.rememberListSearch
import io.personal.filemanager.ui.components.ListSearchState
import io.personal.filemanager.ui.components.ListSearchField
import io.personal.filemanager.ui.components.ListSearchButton
import io.personal.filemanager.ui.components.rememberFileView
import io.personal.filemanager.ui.components.ViewModeButton
import io.personal.filemanager.ui.components.tapClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.testTag
import kotlin.math.abs
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.AppContainer
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.InstalledApp
import io.personal.filemanager.data.FileEntry
import java.io.File
import io.personal.filemanager.data.MediaCategory
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.components.EmptyState
import io.personal.filemanager.ui.components.KindBadge
import io.personal.filemanager.ui.components.LoadingBox
import io.personal.filemanager.ui.components.RefreshWhileVisible
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.components.toImage
import io.personal.filemanager.core.FileKind
import io.personal.filemanager.core.SectionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsViewModel(private val container: AppContainer) : ViewModel() {
    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    val apps: StateFlow<List<InstalledApp>?> = _apps.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _apps.value = runCatching { container.apps.launchableApps() }.getOrDefault(emptyList())
        }
    }
}

/** Индекс вкладки приложения после горизонтального свайпа. */
fun nextAppTab(current: Int, left: Boolean): Int =
    if (left) (current + 1).coerceAtMost(1) else (current - 1).coerceAtLeast(0)

/**
 * «Приложения»: установленные (запуск, системные сведения) и локальные APK-файлы.
 * Экспорт APK и split-наборов (.apks), отправка; установка APK-файлов — через экран APK.
 */
@Composable
fun AppsScreen() {
    val navigator = LocalNavigator.current
    val pager = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val selectedTab = pager.currentPage
    Column(Modifier.fillMaxSize()) {
        val apkView = rememberFileView(MediaCategory.APKS.name)
        val search = rememberListSearch()
        ScreenTopBar(
            title = SectionId.APPS.title,
            onBack = { navigator.back() },
            actions = {
                ListSearchButton(search)
                if (selectedTab == 1) ViewModeButton(apkView)
            },
        )
        PrimaryTabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(
                selected = selectedTab == 0,
                onClick = { scope.launch { pager.animateScrollToPage(0) } },
                text = { Text("Установленные") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { scope.launch { pager.animateScrollToPage(1) } },
                text = { Text("APK-файлы") },
            )
        }
        ListSearchField(search)
        // Compose Pager owns horizontal swipes and coordinates them with vertical
        // lists. No global swipe navigation between main sections.
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().testTag("apps-swipe-content"),
            beyondViewportPageCount = 0,
        ) { page ->
            if (page == 0) InstalledAppsList(search)
            else MediaListBody(MediaCategory.APKS, search = search)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstalledAppsList(search: ListSearchState) {
    val context = LocalContext.current
    val vm = appSharedViewModel(key = "installed-apps") { AppsViewModel(it) }
    val apps by vm.apps.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<InstalledApp?>(null) }
    RefreshWhileVisible(null) { vm.refresh() }
    val all = apps
    val list = all?.filter { search.matches(it.label, it.packageName) }
    when {
        list == null -> LoadingBox()
        all.isNullOrEmpty() -> EmptyState(Icons.Filled.Android, "Нет данных", "Не удалось получить список приложений.")
        list.isEmpty() -> EmptyState(Icons.Filled.Android, "Ничего не найдено", "Измените запрос.")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Text(
                    "Приложения с иконкой на рабочем столе: ${list.size}. Нажмите, чтобы открыть.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(list, key = { it.packageName }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (appLaunch(context, app.packageName) == null)
                                    FileActions.toast(context, "Приложение нельзя запустить")
                            },
                            onLongClick = { actionsFor = app },
                        )
                        .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app.packageName)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                app.versionName?.let { "v$it" },
                                formatSize(app.sizeBytes),
                                if (app.apkPaths.size > 1) "split: ${app.apkPaths.size} файла" else null,
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    actionsFor?.let { app -> AppMenu(app, onDismiss = { actionsFor = null }) }
}

private fun appLaunch(context: android.content.Context, packageName: String): android.content.Intent? {
    val container = (context.applicationContext as io.personal.filemanager.FileManagerApp).container
    val intent = container.apps.launchIntent(packageName) ?: return null
    return runCatching {
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        intent
    }.getOrNull()
}

@Composable
private fun AppIcon(packageName: String) {
    val apps = appContainer().apps
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) { apps.icon(packageName)?.toImage() }
    }
    val image = icon
    if (image != null) {
        Image(image, contentDescription = null, modifier = Modifier.size(44.dp))
    } else {
        KindBadge(FileKind.APK, 44.dp)
    }
}

/** Контекстное меню по удержанию приложения, без `⋮` в каждом ряду. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AppMenu(app: InstalledApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = io.personal.filemanager.ui.theme.AppColors.Surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.packageName)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Spacer(Modifier.padding(8.dp))
            TextButton(onClick = {
                onDismiss()
                if (appLaunch(context, app.packageName) == null)
                    FileActions.toast(context, "Приложение нельзя запустить")
            }, modifier = Modifier.fillMaxWidth()) { Text("Запустить") }
            TextButton(onClick = {
                onDismiss()
                val dest = File(container.storage.downloadsPath, EXPORT_DIR)
                container.operations.exportApk(app.label, app.versionName, app.apkPaths, dest)
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (app.apkPaths.size > 1) "Экспортировать набор .apks" else "Экспортировать APK")
            }
            TextButton(onClick = {
                onDismiss()
                val dest = File(context.cacheDir, "shared-apk").apply { deleteRecursively(); mkdirs() }
                container.operations.exportApk(app.label, app.versionName, app.apkPaths, dest) { file ->
                    FileActions.share(context, listOf(FileEntry.from(file)))
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Поделиться APK") }
            TextButton(onClick = { onDismiss(); FileActions.openAppDetails(context, app.packageName) },
                modifier = Modifier.fillMaxWidth()) { Text("Сведения о приложении") }
        }
    }
}

/** Папка экспорта внутри «Загрузок». */
const val EXPORT_DIR = "Экспорт APK"
