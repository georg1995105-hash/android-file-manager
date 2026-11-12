package io.personal.filemanager.ui.analyzer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.AppContainer
import io.personal.filemanager.core.FileKind
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.analyzer.AnalysisResult
import io.personal.filemanager.data.analyzer.AnalyzedFile
import io.personal.filemanager.data.analyzer.StorageAnalyzer
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.Routes
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.components.FileThumbnail
import io.personal.filemanager.ui.components.KindBadge
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.ops.LocalFileOps
import io.personal.filemanager.ui.theme.AppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AnalyzerState(
    val scanning: Boolean = false,
    val progress: Int = 0,
    val result: AnalysisResult? = null,
    val error: String? = null,
)

/**
 * Stores the last completed scan for this process. Returning to the screen or switching
 * tabs must not re-hash thousands of files. Refresh is always explicit after first scan.
 */
class AnalyzerViewModel(private val container: AppContainer) : ViewModel() {
    companion object {
        @Volatile private var cachedResult: AnalysisResult? = null
    }
    private val _state = MutableStateFlow(AnalyzerState(result = cachedResult))
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        if (!container.storage.hasAllFilesAccess()) {
            _state.value = _state.value.copy(scanning = false, error = "Разрешите доступ ко всем файлам Android")
            return
        }
        _state.value = _state.value.copy(scanning = true, progress = 0, error = null)
        job = viewModelScope.launch {
            try {
                val scan = withContext(Dispatchers.IO) {
                    StorageAnalyzer().scan(
                        root = File(container.storage.rootPath),
                        onProgress = { n -> _state.value = _state.value.copy(progress = n) },
                        cancelled = { !isActive },
                    )
                }
                cachedResult = scan
                _state.value = AnalyzerState(result = scan, progress = scan.scannedFiles)
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(scanning = false, error = if (_state.value.result == null) "Анализ остановлен" else null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(scanning = false, error = e.message ?: "Не удалось прочитать память")
            }
        }
    }

    fun cancel() { job?.cancel() }
}

private const val DUPLICATE_MIN_SIZE = 64 * 1024L

@Composable
fun AnalyzerScreen() {
    val vm = appViewModel { AnalyzerViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { if (vm.state.value.result == null) vm.start() }
    val nav = LocalNavigator.current
    val fileOps = LocalFileOps.current
    val storage = appContainer().storage
    val info = remember(storage) { storage.internalStorage() }
    val usedFraction = if (info.totalBytes > 0L) {
        (info.usedBytes.toDouble() / info.totalBytes).toFloat().coerceIn(0f, 1f)
    } else 0f

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openedCategory by remember { mutableStateOf<String?>(null) }
    var openedDuplicate by remember { mutableStateOf<String?>(null) }
    var includeSmall by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val result = state.result
    val visibleDuplicates = remember(result, includeSmall) {
        result?.duplicates?.filter { includeSmall || it.bytesPerFile >= DUPLICATE_MIN_SIZE }.orEmpty()
    }

    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }
    fun open(path: String) {
        val file = File(path)
        if (file.isFile) nav.openFile(FileEntry.from(file))
    }

    Column(Modifier.fillMaxSize().background(AppColors.Background)) {
        ScreenTopBar(
            title = "Анализ памяти",
            onBack = { nav.back() },
            actions = {
                TextButton(onClick = vm::start, enabled = !state.scanning) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Обновить")
                }
            },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.Card),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        Text("Память телефона", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${formatSize(info.usedBytes)} / ${formatSize(info.totalBytes)}",
                            fontSize = 29.sp, fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextPrimary,
                        )
                        LinearProgressIndicator(
                            progress = { usedFraction },
                            modifier = Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(8.dp)),
                            color = AppColors.Accent,
                            trackColor = AppColors.CardBorder,
                        )
                        Text(
                            "Занято на устройстве · ${(usedFraction * 100).toInt()}%",
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (state.scanning) {
                item {
                    Surface(color = AppColors.Surface, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(
                                if (result == null) "Сканируем доступные файлы" else "Обновляем результаты",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Обработано ${state.progress} файлов. Предыдущие результаты сохранены.",
                                color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = vm::cancel) { Text("Остановить") }
                        }
                    }
                }
            }
            if (state.error != null) {
                item {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }

            if (result != null) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${formatSize(result.scannedBytes)} доступных файлов",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${result.scannedFiles} файлов проверено",
                                color = AppColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                item {
                    if (result.incomplete) {
                        Text(
                            "Системные и закрытые данные Android сюда не входят.",
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Обзор", "Крупные", "Дубликаты").forEachIndexed { index, title ->
                            val active = index == tab
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (active) AppColors.AccentContainer else AppColors.Card)
                                    .clickable {
                                        tab = index
                                        selected = emptySet()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    title,
                                    color = if (active) AppColors.OnAccentContainer else AppColors.TextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                when (tab) {
                    0 -> {
                        item { SectionHeading("По типам файлов", "Нажмите категорию, чтобы увидеть крупные файлы") }
                        items(groupedCategories(result), key = { "kind-${it.name}" }) { category ->
                            val opened = openedCategory == category.name
                            Card(
                                onClick = { openedCategory = if (opened) null else category.name },
                                colors = CardDefaults.cardColors(containerColor = AppColors.Card),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        KindBadge(category.kinds.firstOrNull() ?: FileKind.OTHER, 52.dp)
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(category.name, style = MaterialTheme.typography.titleMedium)
                                            Text("${category.count} файлов", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                        }
                                        Text(formatSize(category.bytes), style = MaterialTheme.typography.titleMedium)
                                    }
                                    Spacer(Modifier.height(11.dp))
                                    LinearProgressIndicator(
                                        progress = {
                                            if (result.scannedBytes > 0L) {
                                                (category.bytes.toDouble() / result.scannedBytes).toFloat().coerceIn(0f, 1f)
                                            } else 0f
                                        },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
                                        color = AppColors.Accent,
                                        trackColor = AppColors.CardBorder,
                                    )
                                    if (opened) {
                                        Spacer(Modifier.height(8.dp))
                                        result.categoryFiles.filterKeys { it in category.kinds }
                                            .values.flatten().sortedByDescending { it.bytes }.take(15)
                                            .forEach { file ->
                                                AnalyzerItem(
                                                    file.path, formatSize(file.bytes),
                                                    selected = file.path in selected,
                                                    onToggle = { toggle(file.path) },
                                                    onOpen = { open(file.path) },
                                                )
                                            }
                                    }
                                }
                            }
                        }
                        item { SectionHeading("Самые объёмные папки", "Учитываются вложенные файлы") }
                        items(result.topFolders.take(12), key = { "folder-${it.path}" }) { folder ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = AppColors.Card,
                                shape = RoundedCornerShape(16.dp),
                                onClick = { nav.open(Routes.browser(folder.path)) },
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    KindBadge(FileKind.FOLDER, 52.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(File(folder.path).name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                        Text("${folder.count} файлов", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                    }
                                    Text(formatSize(folder.bytes), style = MaterialTheme.typography.bodyMedium)
                                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AppColors.TextSecondary)
                                }
                            }
                        }
                    }
                    1 -> {
                        item { SectionHeading("Крупные файлы", "Отметьте галочками ненужные файлы") }
                        items(result.largeFiles, key = { "large-${it.path}" }) { file ->
                            AnalyzerItem(
                                path = file.path,
                                size = formatSize(file.bytes),
                                selected = file.path in selected,
                                onToggle = { toggle(file.path) },
                                onOpen = { open(file.path) },
                            )
                        }
                    }
                    2 -> {
                        item {
                            SectionHeading(
                                "Одинаковые файлы",
                                "Совпадение подтверждено SHA-256. Оригиналы не удаляются автоматически.",
                            )
                        }
                        item {
                            val bytes = visibleDuplicates.sumOf { it.reclaimableBytes }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Возможная экономия", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                    Text(formatSize(bytes), style = MaterialTheme.typography.titleLarge, color = AppColors.Accent)
                                }
                                TextButton(onClick = { includeSmall = !includeSmall; selected = emptySet() }) {
                                    Text(if (includeSmall) "Скрыть мелкие" else "Показать мелкие")
                                }
                            }
                        }
                        if (visibleDuplicates.isEmpty()) {
                            item { Text("Крупных дубликатов не обнаружено.", color = AppColors.TextSecondary) }
                        }
                        items(visibleDuplicates, key = { "dup-${it.files.firstOrNull()?.path ?: it.bytesPerFile}" }) { group ->
                            val groupKey = group.files.firstOrNull()?.path.orEmpty()
                            val opened = openedDuplicate == groupKey
                            val extraPaths = group.files.drop(1).map { it.path }
                            Card(
                                colors = CardDefaults.cardColors(containerColor = AppColors.Card),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            openedDuplicate = if (opened) null else groupKey
                                        },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val first = group.files.firstOrNull()
                                        if (first != null) {
                                            AnalyzerThumb(first.path, 56)
                                            Spacer(Modifier.width(12.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text("${group.files.size} одинаковых файла", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                "${formatSize(group.bytesPerFile)} каждый · ${formatSize(group.reclaimableBytes)} повторяется",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = AppColors.TextSecondary,
                                            )
                                        }
                                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AppColors.TextSecondary)
                                    }
                                    if (opened) {
                                        Spacer(Modifier.height(12.dp))
                                        TextButton(
                                            onClick = { selected = selected + extraPaths },
                                            enabled = extraPaths.isNotEmpty(),
                                        ) { Text("Выбрать копии (оставить один)") }
                                        group.files.forEach { item ->
                                            AnalyzerItem(
                                                path = item.path,
                                                size = if (item.path == group.files.first().path) "Первый экземпляр" else "",
                                                selected = item.path in selected,
                                                onToggle = { toggle(item.path) },
                                                onOpen = { open(item.path) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (result.duplicatesIncomplete) {
                            item {
                                Text("Проверка дубликатов ограничена объёмом чтения. Показаны только подтверждённые совпадения.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                            }
                        }
                    }
                }
            } else if (!state.scanning) {
                item {
                    Surface(color = AppColors.Card, shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("Анализ ещё не выполнен", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = vm::start) { Text("Начать анализ") }
                        }
                    }
                }
            }
        }

        if (selected.isNotEmpty()) {
            HorizontalDivider(color = AppColors.CardBorder)
            Row(
                modifier = Modifier.fillMaxWidth().background(AppColors.Surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${selected.size} выбрано", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { selected = emptySet() }) { Text("Снять") }
                Button(
                    onClick = {
                        val files = selected.map(::File).filter { it.isFile }
                        if (files.isNotEmpty()) {
                            // Routes through FileOpsHost: it displays the existing delete confirmation.
                            fileOps.delete(files.map(FileEntry::from))
                        }
                        selected = emptySet()
                    },
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Удалить")
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
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
private fun AnalyzerThumb(path: String, size: Int) {
    val file = remember(path) { File(path) }
    if (file.exists()) FileThumbnail(remember(path) { FileEntry.from(file) }, size.dp)
    else KindBadge(FileKind.OTHER, size.dp)
}

@Composable
private fun AnalyzerItem(
    path: String,
    size: String,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val file = remember(path) { File(path) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(82.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) AppColors.AccentContainer else Color.Transparent)
                .clickable(onClick = onOpen)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnalyzerThumb(path, 56)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    file.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    listOfNotNull(file.parentFile?.name, size.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = AppColors.Divider)
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
