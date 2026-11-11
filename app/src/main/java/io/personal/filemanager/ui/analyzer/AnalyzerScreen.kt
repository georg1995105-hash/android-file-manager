package io.personal.filemanager.ui.analyzer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.AppContainer
import io.personal.filemanager.core.FileKind
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.analyzer.AnalysisResult
import io.personal.filemanager.data.analyzer.StorageAnalyzer
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.Routes
import io.personal.filemanager.ui.ops.LocalFileOps
import io.personal.filemanager.ui.theme.AppColors
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.components.ScreenTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AnalyzerState(val scanning: Boolean = false, val progress: Int = 0,
    val result: AnalysisResult? = null, val error: String? = null)

class AnalyzerViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AnalyzerState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        if (!container.storage.hasAllFilesAccess()) {
            _state.value = AnalyzerState(error = "Разреши приложению доступ ко всем файлам Android")
            return
        }
        _state.value = AnalyzerState(scanning = true)
        job = viewModelScope.launch {
            try {
                val scan = withContext(Dispatchers.IO) {
                    StorageAnalyzer().scan(
                        root = File(container.storage.rootPath),
                        onProgress = { n -> _state.value = _state.value.copy(progress = n) },
                        cancelled = { !isActive },
                    )
                }
                _state.value = AnalyzerState(result = scan, progress = scan.scannedFiles)
            } catch (_: CancellationException) {
                _state.value = AnalyzerState(error = "Сканирование отменено")
            } catch (e: Exception) {
                _state.value = AnalyzerState(error = e.message ?: "Ошибка чтения памяти")
            }
        }
    }

    fun cancel() { job?.cancel() }
}

@Composable
fun AnalyzerScreen() {
    val vm = appViewModel { AnalyzerViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Сканирование стартует при открытии экрана, а не только после нажатия «Повторить».
    LaunchedEffect(vm) { vm.start() }
    val nav = LocalNavigator.current
    val storage = appContainer().storage
    val info = remember(storage) { storage.internalStorage() }
    var activeTab by rememberSaveable { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showAll by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var expandedCategories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showSmallDuplicates by rememberSaveable { mutableStateOf(false) }
    val result = state.result
    val fill = if (info.totalBytes > 0) {
        (info.usedBytes.toDouble() / info.totalBytes).toFloat().coerceIn(0f, 1f)
    } else 0f

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Анализ памяти", onBack = { nav.back() })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Память устройства", style = MaterialTheme.typography.titleMedium)
                        Text("${formatSize(info.usedBytes)} из ${formatSize(info.totalBytes)}",
                            style = MaterialTheme.typography.titleLarge)
                        LinearProgressIndicator(progress = { fill }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "Общий объём занятой памяти включает системные данные, " +
                                "которые Android не позволяет просканировать.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.scanning) {
                item {
                    Text("Идёт анализ: ${state.progress} файлов", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = vm::cancel) { Text("Остановить") }
                }
            } else if (state.error != null) {
                item {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::start) { Text("Повторить анализ") }
                }
            } else if (result != null) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Файлов: ${result.scannedFiles}", style = MaterialTheme.typography.titleSmall)
                            Text("Найдено: ${formatSize(result.scannedBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = vm::start) { Text("Обновить") }
                    }
                    if (result.incomplete) Text(
                        "Некоторые системные папки недоступны. Данные по типам могут быть неполными.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("Обзор", "Папки", "Крупные", "Дубликаты").forEachIndexed { index, name ->
                            FilterChip(
                                selected = activeTab == index,
                                onClick = { activeTab = index },
                                label = { Text(name) },
                            )
                        }
                    }
                }
                when (activeTab) {
                    0 -> {
                        item {
                            Text("Размеры найденных файлов", style = MaterialTheme.typography.titleMedium)
                            Text("Не вся занятая память телефона: только доступные файлы.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        itemsIndexed(groupedCategories(result)) { _, category ->
                            val opened = category.name in expandedCategories
                            val proportion = if (result.scannedBytes > 0)
                                (category.bytes.toDouble() / result.scannedBytes).toFloat().coerceIn(0f, 1f)
                            else 0f
                            Card(
                                onClick = {
                                    expandedCategories = if (opened) expandedCategories - category.name
                                        else expandedCategories + category.name
                                },
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = AppColors.Card),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(category.name, modifier = Modifier.weight(1f))
                                        Text(formatSize(category.bytes))
                                    }
                                    LinearProgressIndicator(
                                        progress = { proportion }, modifier = Modifier.fillMaxWidth())
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${category.count} файлов",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(if (opened) "Свернуть" else "Посмотреть",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = AppColors.Accent)
                                    }
                                    if (opened) {
                                        val samples = result.categoryFiles.filterKeys { it in category.kinds }
                                            .values.flatten().sortedByDescending { it.bytes }.take(15)
                                        Text("Крупнейшие файлы категории (до 15)", style = MaterialTheme.typography.bodySmall,
                                            color = AppColors.TextSecondary)
                                        if (samples.isEmpty()) Text("Файлы не найдены.")
                                        samples.forEach { file ->
                                            AnalyzerFileRow(file.path, formatSize(file.bytes)) {
                                                nav.openFile(FileEntry.from(File(file.path)))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        item {
                            Text("Папки по занятому месту", style = MaterialTheme.typography.titleMedium)
                            Text("Учитываются вложенные файлы. Нажмите папку, чтобы перейти к её содержимому.",
                                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                        if (result.topFolders.isEmpty()) item { Text("Папки с файлами не найдены.") }
                        itemsIndexed(result.topFolders) { _, folder ->
                            Card(
                                onClick = { nav.open(Routes.browser(folder.path)) },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = AppColors.Card),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = AppColors.Accent)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(File(folder.path).name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${folder.count} файлов • ${folder.path}",
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(formatSize(folder.bytes), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    2 -> {
                        item {
                            Text("Самые большие файлы", style = MaterialTheme.typography.titleMedium)
                            Text("Нажми файл, чтобы открыть. Показаны 25 самых крупных.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        itemsIndexed(result.largeFiles) { _, file ->
                            AnalyzerFileRow(file.path, formatSize(file.bytes)) {
                                nav.openFile(FileEntry.from(File(file.path)))
                            }
                        }
                    }
                    3 -> {
                        item {
                            Text("Подтверждённые дубликаты", style = MaterialTheme.typography.titleMedium)
                            Text("Совпадение SHA-256. Ничего не удаляется автоматически.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "Повторяющийся объём: ${formatSize(result.duplicates.sumOf { it.reclaimableBytes })}",
                                style = MaterialTheme.typography.titleSmall)
                        }
                        val filteredGroups = result.duplicates.filter {
                            showSmallDuplicates || it.bytesPerFile >= 64 * 1024
                        }
                        if (filteredGroups.size != result.duplicates.size) {
                            item {
                                TextButton(onClick = {
                                    showSmallDuplicates = true
                                    expanded = emptySet()
                                    showAll = emptySet()
                                }) { Text("Показать мелкие дубликаты (до 64 КБ): ${result.duplicates.size - filteredGroups.size} групп") }
                            }
                        } else if (showSmallDuplicates) {
                            item {
                                TextButton(onClick = {
                                    showSmallDuplicates = false
                                    expanded = emptySet()
                                    showAll = emptySet()
                                }) { Text("Скрыть мелкие дубликаты") }
                            }
                        }
                        if (filteredGroups.isEmpty()) {
                            item { Text("Крупные дубликаты не найдены.") }
                        }
                        itemsIndexed(filteredGroups) { index, group ->
                            val opened = index in expanded
                            Card(
                                onClick = {
                                    expanded = if (opened) expanded - index else expanded + index
                                },
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("${group.files.size} одинаковых файлов",
                                        style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "По ${formatSize(group.bytesPerFile)} · повторяется ${formatSize(group.reclaimableBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (opened) "Свернуть" else "Посмотреть файлы",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (opened) {
                                val visible = if (index in showAll) group.files else group.files.take(8)
                                visible.forEach { file ->
                                    AnalyzerFileRow(file.path, "") {
                                        nav.openFile(FileEntry.from(File(file.path)))
                                    }
                                }
                                if (visible.size < group.files.size) {
                                    TextButton(onClick = { showAll = showAll + index }) {
                                        Text("Показать остальные ${group.files.size - visible.size}")
                                    }
                                }
                            }
                        }
                        if (result.duplicatesIncomplete) item {
                            Text("Не все файлы проверены: действует ограничение объёма чтения.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private data class DisplayCategory(
    val name: String, val bytes: Long, val count: Int, val kinds: Set<FileKind>,
)

private fun groupedCategories(result: AnalysisResult): List<DisplayCategory> =
    result.categories.groupBy { categoryName(it.kind) }.map { (name, entries) ->
        DisplayCategory(name, entries.sumOf { it.bytes }, entries.sumOf { it.count }, entries.map { it.kind }.toSet())
    }.sortedByDescending { it.bytes }

@Composable
private fun AnalyzerFileRow(path: String, size: String, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val nav = LocalNavigator.current
    val fileOps = LocalFileOps.current
    val file = remember(path) { File(path) }
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                Text(file.parentFile?.name.orEmpty(), maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary)
            }
            if (size.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(size, style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
            }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Действия с файлом")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Открыть папку") },
                        leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                        onClick = {
                            menu = false
                            file.parentFile?.let { nav.open(Routes.browser(it.absolutePath)) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("В корзину…") },
                        leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
                        onClick = {
                            menu = false
                            if (file.isFile) fileOps.delete(listOf(FileEntry.from(file)))
                        },
                    )
                }
            }
        }
    }
}

private fun categoryName(kind: FileKind): String = when (kind) {
    FileKind.IMAGE -> "Фото"
    FileKind.VIDEO -> "Видео"
    FileKind.AUDIO -> "Аудио"
    FileKind.DOCUMENT, FileKind.TEXT, FileKind.PDF -> "Документы"
    FileKind.ARCHIVE -> "Архивы"
    FileKind.APK -> "APK"
    FileKind.FOLDER -> "Папки"
    FileKind.OTHER -> "Прочие"
}
