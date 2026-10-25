package io.personal.filemanager.ui.sections

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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

/**
 * «Приложения»: установленные (запуск, системные сведения) и локальные APK-файлы.
 * Экспорт APK и split-наборов (.apks), отправка; установка APK-файлов — через экран APK.
 */
@Composable
fun AppsScreen() {
    val navigator = LocalNavigator.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = SectionId.APPS.title, onBack = { navigator.back() })
        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Установленные") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("APK-файлы") })
        }
        if (tab == 0) InstalledAppsList() else MediaListBody(MediaCategory.APKS)
    }
}

@Composable
private fun InstalledAppsList() {
    val context = LocalContext.current
    val vm = appViewModel { AppsViewModel(it) }
    val apps by vm.apps.collectAsStateWithLifecycle()
    RefreshWhileVisible(null) { vm.refresh() }
    val list = apps
    when {
        list == null -> LoadingBox()
        list.isEmpty() -> EmptyState(Icons.Filled.Android, "Нет данных", "Не удалось получить список приложений.")
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
                        .clickable {
                            val intent = appLaunch(context, app.packageName)
                            if (intent == null) FileActions.toast(context, "Приложение нельзя запустить")
                        }
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
                    AppMenu(app)
                }
            }
        }
    }
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

/** Действия с установленным приложением: экспорт APK/набора, отправка, системные сведения. */
@Composable
private fun AppMenu(app: InstalledApp) {
    val context = LocalContext.current
    val container = appContainer()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Действия с «${app.label}»")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Запустить") },
                onClick = {
                    open = false
                    if (appLaunch(context, app.packageName) == null) FileActions.toast(context, "Приложение нельзя запустить")
                },
            )
            DropdownMenuItem(
                text = { Text(if (app.apkPaths.size > 1) "Экспорт набора (.apks)" else "Экспорт APK") },
                onClick = {
                    open = false
                    val dest = File(container.storage.downloadsPath, EXPORT_DIR)
                    container.operations.exportApk(app.label, app.versionName, app.apkPaths, dest)
                },
            )
            DropdownMenuItem(
                text = { Text("Поделиться APK") },
                onClick = {
                    open = false
                    val dest = File(context.cacheDir, "shared-apk").apply { deleteRecursively(); mkdirs() }
                    container.operations.exportApk(app.label, app.versionName, app.apkPaths, dest) { file ->
                        FileActions.share(context, listOf(FileEntry.from(file)))
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Сведения (системные)") },
                onClick = { open = false; FileActions.openAppDetails(context, app.packageName) },
            )
        }
    }
}

/** Папка экспорта внутри «Загрузок». */
const val EXPORT_DIR = "Экспорт APK"
