package io.personal.filemanager.ui.sections

import io.personal.filemanager.ui.components.rememberFileView
import io.personal.filemanager.ui.components.ViewModeButton
import io.personal.filemanager.ui.components.FilesPane
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.AppContainer
import io.personal.filemanager.data.CreateFolderResult
import io.personal.filemanager.core.FileKind
import io.personal.filemanager.data.FolderResult
import io.personal.filemanager.data.settings.BrowserPrefs
import io.personal.filemanager.data.settings.BrowserSort
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.ops.LocalFileOps
import io.personal.filemanager.ui.components.AllFilesAccessRequired
import io.personal.filemanager.ui.components.DateShown
import io.personal.filemanager.ui.components.EmptyState
import io.personal.filemanager.ui.components.LoadingBox
import io.personal.filemanager.ui.components.RefreshWhileVisible
import io.personal.filemanager.ui.components.QuickCreateDialog
import io.personal.filemanager.ui.Routes
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.components.card
import io.personal.filemanager.ui.components.rememberAllFilesAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BrowserViewModel(private val container: AppContainer) : ViewModel() {
    private val _result = MutableStateFlow<FolderResult?>(null)
    val result: StateFlow<FolderResult?> = _result.asStateFlow()

    val prefs: StateFlow<BrowserPrefs> =
        container.settings.browserPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, BrowserPrefs())

    private var job: Job? = null
    private var loadedPath: String? = null

    fun load(path: String) {
        job?.cancel()
        if (loadedPath != path) _result.value = null
        loadedPath = path
        job = viewModelScope.launch(Dispatchers.IO) {
            val prefs = container.settings.browserPrefs.first()
            _result.value = container.fileSystem.list(path, prefs)
        }
    }

    fun updatePrefs(path: String, transform: (BrowserPrefs) -> BrowserPrefs) {
        viewModelScope.launch {
            container.settings.setBrowserPrefs(transform(prefs.value))
            load(path)
        }
    }

    suspend fun createFolder(parent: String, name: String): CreateFolderResult =
        withContext(Dispatchers.IO) { container.fileSystem.createFolder(parent, name) }

    suspend fun createFile(parent: String, name: String): CreateFolderResult =
        withContext(Dispatchers.IO) { container.fileSystem.createFile(parent, name) }
}

/**
 * Обзор папок. [rootPath] — граница «вверх» (корень памяти или «Загрузки»).
 * Текущая папка переживает поворот экрана; «назад» поднимается на уровень выше.
 */
@Composable
fun BrowserScreen(title: String, rootPath: String, viewKey: String = "FILES") {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val hasAccess = rememberAllFilesAccess()
    val vm = appViewModel(key = "browser-$rootPath") { BrowserViewModel(it) }
    val result by vm.result.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var path by rememberSaveable(rootPath) { mutableStateOf(rootPath) }
    var sortMenu by remember { mutableStateOf(false) }
    var createMenu by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }

    var pendingEditPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(createMenu, pendingEditPath) {
        val next = pendingEditPath ?: return@LaunchedEffect
        if (!createMenu) {
            pendingEditPath = null
            navigator.open(Routes.edit(next))
        }
    }
    val view = rememberFileView(viewKey)

    // Доступ проверяется системой при каждом чтении, поэтому после выдачи разрешения список появится сам.
    RefreshWhileVisible(null) { vm.load(path) }

    // После операций (копирование, удаление…) содержимое папки перечитывается.
    val operations = appContainer().operations
    val opsVersion by operations.version.collectAsStateWithLifecycle()
    val clipboard by operations.clipboard.collectAsStateWithLifecycle()
    val running by operations.running.collectAsStateWithLifecycle()
    LaunchedEffect(opsVersion) { if (opsVersion > 0) vm.load(path) }
    val fileOps = LocalFileOps.current
    DisposableEffect(path, hasAccess) {
        if (hasAccess) fileOps.setCurrentFolder(path)
        onDispose { fileOps.setCurrentFolder(null) }
    }

    fun go(newPath: String) {
        path = newPath
        vm.load(newPath)
    }

    val canGoUp = File(path).absolutePath != File(rootPath).absolutePath
    BackHandler(enabled = canGoUp) {
        File(path).parent?.let { go(it) }
    }

    val relative = File(path).absolutePath.removePrefix(File(rootPath).absolutePath).trim('/')

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = if (relative.isEmpty()) title else File(path).name,
            subtitle = if (relative.isEmpty()) path else "$title/$relative",
            onBack = { if (canGoUp) File(path).parent?.let { go(it) } else navigator.back() },
            actions = {
                IconButton(onClick = { navigator.openSearch() }) {
                    Icon(Icons.Filled.Search, contentDescription = "Поиск")
                }
                ViewModeButton(view)
                IconButton(onClick = { actionsMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Меню")
                }
                DropdownMenu(expanded = actionsMenu, onDismissRequest = { actionsMenu = false }) {
                    if (hasAccess) {
                        DropdownMenuItem(
                            text = { Text("Создать файл или папку") },
                            onClick = { actionsMenu = false; createMenu = true },
                        )
                    }
                }
            },
        )
        if (!hasAccess) {
            AllFilesAccessRequired()
            return@Column
        }
        Column(Modifier.weight(1f)) {
            when (val r = result) {
                null -> LoadingBox()
                FolderResult.RestrictedByAndroid -> EmptyState(
                    Icons.Filled.Lock,
                    "Папка закрыта Android",
                    "Начиная с Android 11 содержимое Android/data и Android/obb недоступно файловым менеджерам " +
                        "без root, даже с доступом ко всем файлам.",
                )
                FolderResult.NotFound -> EmptyState(Icons.Filled.FolderOff, "Папка не найдена", "Возможно, она перемещена или удалена.")
                FolderResult.AccessDenied -> EmptyState(Icons.Filled.FolderOff, "Нет доступа", "Android не разрешает читать эту папку.")
                is FolderResult.Ok -> if (r.entries.isEmpty()) {
                    EmptyState(Icons.Filled.FolderOpen, "Пустая папка", "Здесь нет файлов" + if (!prefs.showHidden) " (скрытые не показаны)." else ".")
                } else {
                    FilesPane(
                        entries = r.entries,
                        view = view,
                        dateShown = DateShown.MODIFIED,
                        onOpen = { entry -> if (entry.isDirectory) go(entry.path) else navigator.openFile(entry, r.entries) },
                    )
                }
            }
        }
        // Нижняя панель действий — в зоне большого пальца (во время вставки её место занимает панель операции).
        if (clipboard == null && running == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .card(RoundedCornerShape(18.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row {
                    TextButton(onClick = { sortMenu = true }) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(prefs.sort.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(
                            if (prefs.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                            contentDescription = if (prefs.descending) "по убыванию" else "по возрастанию",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        BrowserSort.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text((if (s == prefs.sort) "✓ " else "") + s.title) },
                                onClick = { sortMenu = false; vm.updatePrefs(path) { it.copy(sort = s) } },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (prefs.descending) "По возрастанию" else "По убыванию") },
                            onClick = { sortMenu = false; vm.updatePrefs(path) { it.copy(descending = !it.descending) } },
                        )
                    }
                }
                IconButton(onClick = { vm.updatePrefs(path) { it.copy(showHidden = !it.showHidden) } }) {
                    Icon(
                        if (prefs.showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (prefs.showHidden) "Скрыть скрытые файлы" else "Показать скрытые файлы",
                    )
                }
            }
        }
    }

    if (createMenu) {
        val storage = appContainer().storage
        QuickCreateDialog(
            initialFolder = path,
            rootFolder = storage.rootPath,
            downloadsFolder = storage.downloadsPath,
            onDismiss = { createMenu = false },
            onCreate = { folder, isFolder, name ->
                if (isFolder) vm.createFolder(folder, name) else vm.createFile(folder, name)
            },
            onCreated = { created ->
                vm.load(path)
                if (!created.isDirectory) {
                    // Новый текстовый файл сразу готов к вводу, без пустого экрана просмотра.
                    pendingEditPath = created.path
                }
            },
        )
    }
}
