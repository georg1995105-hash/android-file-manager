package io.personal.filemanager.ui.archive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.AppContainer
import io.personal.filemanager.core.FileTypes
import io.personal.filemanager.core.formatShortDate
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.core.pluralRu
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.archive.ArchiveFormats
import io.personal.filemanager.data.archive.ArchiveItem
import io.personal.filemanager.data.archive.ExtractLimits
import io.personal.filemanager.data.archive.ListResult
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.components.EmptyState
import io.personal.filemanager.ui.components.KindBadge
import io.personal.filemanager.ui.components.LoadingBox
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.components.card
import io.personal.filemanager.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Узел дерева архива: элемент или папка, выведенная из путей. */
data class ArchiveNode(val path: String, val name: String, val isDirectory: Boolean, val item: ArchiveItem?, val childCount: Int)

/** Строит содержимое папки [dir] архива, включая неявные папки (многие архивы их не хранят). */
fun childrenOf(items: List<ArchiveItem>, dir: String): List<ArchiveNode> {
    val prefix = if (dir.isEmpty()) "" else "$dir/"
    val dirs = linkedMapOf<String, Int>()
    val files = mutableListOf<ArchiveNode>()
    for (it in items) {
        if (!it.path.startsWith(prefix) || it.path == dir) continue
        val rest = it.path.removePrefix(prefix)
        val first = rest.substringBefore('/')
        if (rest.contains('/') || it.isDirectory) {
            val path = prefix + first
            dirs[path] = (dirs[path] ?: 0) + if (rest.contains('/')) 1 else 0
        } else {
            files += ArchiveNode(it.path, first, false, it, 0)
        }
    }
    return dirs.map { (p, c) -> ArchiveNode(p, p.substringAfterLast('/'), true, items.firstOrNull { it.path == p }, c) }
        .sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }
}

data class ArchiveUiState(
    val loading: Boolean = true,
    val result: ListResult? = null,
    val askPassword: Boolean = false,
    val wrongPassword: Boolean = false,
    val previewBusy: Boolean = false,
)

class ArchiveViewModel(private val container: AppContainer, val path: String) : ViewModel() {
    private val _state = MutableStateFlow(ArchiveUiState())
    val state: StateFlow<ArchiveUiState> = _state.asStateFlow()

    /** Пароль хранится только в памяти этого экрана. */
    var password: CharArray? = null
        private set

    private val previewRoot = File(container.app.cacheDir, "archive-preview/${System.nanoTime()}")

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { container.operations.archives.list(File(path), password) }
            _state.value = ArchiveUiState(
                loading = false,
                result = r,
                askPassword = r == ListResult.PasswordRequired || r == ListResult.WrongPassword,
                wrongPassword = r == ListResult.WrongPassword,
            )
        }
    }

    fun submitPassword(value: String) {
        password?.fill('\u0000')
        password = value.toCharArray()
        _state.value = _state.value.copy(askPassword = false)
        if (_state.value.result !is ListResult.Ok) load()
    }

    fun requestPassword(wrong: Boolean) {
        _state.value = _state.value.copy(askPassword = true, wrongPassword = wrong)
    }

    fun dismissPassword() {
        _state.value = _state.value.copy(askPassword = false)
    }

    val needsPasswordForData: Boolean
        get() = (_state.value.result as? ListResult.Ok)?.encrypted == true && password == null

    fun extract(selected: Set<String>?, destParent: File) {
        if (needsPasswordForData) {
            requestPassword(false)
            return
        }
        container.operations.extract(File(path), destParent, password?.copyOf(), selected) { wrong ->
            viewModelScope.launch { requestPassword(wrong) }
        }
    }

    /** Извлекает один файл во временную папку приложения для просмотра. */
    fun preview(item: ArchiveItem, onReady: (File) -> Unit, onError: (String) -> Unit) {
        if (needsPasswordForData) {
            requestPassword(false)
            return
        }
        _state.value = _state.value.copy(previewBusy = true)
        viewModelScope.launch {
            previewRoot.mkdirs()
            val limits = ExtractLimits(maxTotalBytes = minOf(previewRoot.usableSpace - 50_000_000, 2_000_000_000))
            val r = withContext(Dispatchers.IO) {
                container.operations.archives.extract(File(path), previewRoot, password, setOf(item.path), limits)
            }
            _state.value = _state.value.copy(previewBusy = false)
            when {
                r.passwordRequired || r.wrongPassword -> requestPassword(r.wrongPassword)
                r.ok -> ArchiveFormats.safeRelativePath(item.path)?.let { File(r.outputDir, it) }?.takeIf { it.isFile }
                    ?.let(onReady) ?: onError("Файл не найден после извлечения")
                else -> onError(r.error ?: "Не удалось извлечь файл")
            }
        }
    }

    override fun onCleared() {
        password?.fill('\u0000')
        previewRoot.deleteRecursively()
    }
}

/** Просмотр архива: навигация по папкам внутри, выборочная распаковка, просмотр отдельного файла. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArchiveScreen(path: String) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val vm = appViewModel(key = "archive-$path") { ArchiveViewModel(it, path) }
    val state by vm.state.collectAsStateWithLifecycle()
    var dir by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var extractTargets by remember { mutableStateOf<Set<String>?>(null) }
    var extractAsk by remember { mutableStateOf(false) }
    val archive = remember(path) { File(path) }
    val downloadsPath = appContainer().storage.downloadsPath

    BackHandler(enabled = dir.isNotEmpty()) { dir = dir.substringBeforeLast('/', "") }

    Column(Modifier.fillMaxSize()) {
        val ok = state.result as? ListResult.Ok
        ScreenTopBar(
            title = if (dir.isEmpty()) archive.name else dir.substringAfterLast('/'),
            subtitle = ok?.let {
                val files = it.items.count { i -> !i.isDirectory }
                val size = it.items.filter { i -> i.size > 0 }.sumOf { i -> i.size }
                "${if (dir.isEmpty()) "" else "$dir • "}${pluralRu(files, "файл", "файла", "файлов")}" +
                    (if (size > 0) " • ${formatSize(size)}" else "") + if (it.encrypted) " • 🔒" else ""
            },
            onBack = { if (dir.isNotEmpty()) dir = dir.substringBeforeLast('/', "") else navigator.back() },
        )
        Column(Modifier.weight(1f)) {
            when (val r = state.result) {
                null -> LoadingBox()
                is ListResult.Ok -> {
                    val nodes = remember(r, dir) { childrenOf(r.items, dir) }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        r.note?.let { note ->
                            item {
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                )
                            }
                        }
                        if (nodes.isEmpty()) item { Text("Пусто", modifier = Modifier.padding(20.dp)) }
                        items(nodes, key = { it.path }) { node ->
                            val isSel = node.path in selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (isSel) AppColors.AccentContainer.copy(alpha = 0.55f) else Color.Transparent)
                                    .combinedClickable(
                                        onClick = {
                                            when {
                                                selected.isNotEmpty() -> selected = if (isSel) selected - node.path else selected + node.path
                                                node.isDirectory -> dir = node.path
                                                else -> node.item?.let { item ->
                                                    vm.preview(item, onReady = { file ->
                                                        navigator.openFile(FileEntry.from(file))
                                                    }, onError = { FileActions.toast(context, it) })
                                                }
                                            }
                                        },
                                        onLongClick = { selected = if (isSel) selected - node.path else selected + node.path },
                                    )
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                KindBadge(FileTypes.kindOf(node.name, node.isDirectory), 44.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(node.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val sub = if (node.isDirectory) {
                                        "папка"
                                    } else {
                                        val item = node.item!!
                                        listOfNotNull(
                                            item.modified.takeIf { it > 0 }?.let(::formatShortDate),
                                            if (item.size >= 0) formatSize(item.size) else "размер неизвестен",
                                            if (item.encrypted) "зашифрован" else null,
                                        ).joinToString(" • ")
                                    }
                                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (selected.isNotEmpty()) {
                                    Icon(
                                        if (isSel) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                        contentDescription = null,
                                        tint = if (isSel) AppColors.Accent else AppColors.TextSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
                ListResult.PasswordRequired, ListResult.WrongPassword -> EmptyState(
                    Icons.Filled.Lock,
                    "Архив защищён паролем",
                    "Введите пароль, чтобы увидеть содержимое.",
                    action = { Button(onClick = { vm.requestPassword(state.result == ListResult.WrongPassword) }) { Text("Ввести пароль") } },
                )
                is ListResult.Unsupported -> EmptyState(
                    Icons.Filled.FolderZip,
                    "Не поддерживается встроенным архиватором",
                    r.reason,
                    action = { Button(onClick = { FileActions.openExternally(context, FileEntry.from(archive)) }) { Text("Открыть в другом приложении") } },
                )
                is ListResult.Failed -> EmptyState(
                    Icons.Filled.ErrorOutline,
                    "Не удалось открыть архив",
                    r.reason,
                    action = { Button(onClick = { FileActions.openExternally(context, FileEntry.from(archive)) }) { Text("Открыть в другом приложении") } },
                )
            }
        }
        if (state.result is ListResult.Ok) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .card(RoundedCornerShape(18.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { selected = emptySet() }) { Text("Сбросить") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { extractTargets = selected; extractAsk = true }) { Text("Распаковать (${selected.size})") }
                } else {
                    Text(
                        "Долгое нажатие — выбор",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { extractTargets = null; extractAsk = true }) { Text("Распаковать всё") }
                }
            }
        }
    }

    if (state.previewBusy) {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Извлечение для просмотра…") },
            containerColor = MaterialTheme.colorScheme.surfaceContainer)
    }

    if (extractAsk) {
        AlertDialog(
            onDismissRequest = { extractAsk = false },
            title = { Text("Куда распаковать?") },
            text = {
                Text(
                    "Будет создана новая папка «${ArchiveFormats.baseName(archive)}». Существующие файлы не перезаписываются; " +
                        "небезопасные пути и ссылки пропускаются.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        extractAsk = false
                        archive.parentFile?.let { vm.extract(extractTargets, it) }
                        selected = emptySet()
                    }) { Text("Рядом с архивом") }
                    TextButton(onClick = {
                        extractAsk = false
                        vm.extract(extractTargets, File(downloadsPath))
                        selected = emptySet()
                    }) { Text("В «Загрузки»") }
                    TextButton(onClick = { extractAsk = false }) { Text("Отмена") }
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }

    if (state.askPassword) {
        PasswordDialog(
            wrong = state.wrongPassword,
            onSubmit = vm::submitPassword,
            onDismiss = vm::dismissPassword,
        )
    }
}

@Composable
fun PasswordDialog(wrong: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Пароль архива") },
        text = {
            Column {
                if (wrong) {
                    Text("Неверный пароль или архив повреждён.", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Пароль не сохраняется и нужен только для этого архива.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = value.isNotEmpty(), onClick = { onSubmit(value) }) { Text("Открыть") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}
