package io.personal.filemanager.ui.analyzer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    val nav = LocalNavigator.current
    val info = remember { appContainer().storage.internalStorage() }
    LaunchedEffect(Unit) { vm.start() }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Анализ памяти", onBack = { nav.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Память устройства", style = MaterialTheme.typography.titleMedium)
            Text("Занято ${formatSize(info.usedBytes)} из ${formatSize(info.totalBytes)}")
            Text("Сканируются доступные файлы общей памяти, без закрытых Android данных и кэша других приложений.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                state.scanning -> {
                    Text("Проверено файлов: ${state.progress}")
                    TextButton(onClick = vm::cancel) { Text("Остановить") }
                }
                state.error != null -> {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::start) { Text("Повторить") }
                }
                state.result != null -> {
                    val r = state.result!!
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Результаты", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = vm::start) { Text("Обновить") }
                    }
                    Text("Файлов: ${r.scannedFiles} · доступный объём: ${formatSize(r.scannedBytes)}")
                    if (r.incomplete) Text(
                        "Данные неполные: недоступные папки или ограничение 150 000 файлов.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("По типам", style = MaterialTheme.typography.titleMedium)
                    r.categories.forEach { cat ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(categoryName(cat.kind) + " (${cat.count})", modifier = Modifier.weight(1f))
                            Text(formatSize(cat.bytes))
                        }
                    }
                    HorizontalDivider()
                    Text("Самые большие файлы", style = MaterialTheme.typography.titleMedium)
                    r.largeFiles.take(15).forEach { item ->
                        Column(Modifier.fillMaxWidth().clickable {
                            nav.openFile(FileEntry.from(File(item.path)))
                        }.padding(vertical = 5.dp)) {
                            Text(item.name, maxLines = 1)
                            Text(formatSize(item.bytes), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    Text("Точные дубликаты (SHA-256)", style = MaterialTheme.typography.titleMedium)
                    if (r.duplicates.isEmpty()) Text("Подтверждённые дубликаты не найдены.")
                    else {
                        Text("Повторяющийся объём: " + formatSize(r.duplicates.sumOf { it.reclaimableBytes }))
                        r.duplicates.forEach { group ->
                            Text("${group.files.size} файла по ${formatSize(group.bytesPerFile)}",
                                style = MaterialTheme.typography.titleSmall)
                            group.files.forEach { item ->
                                Text(item.path, modifier = Modifier.fillMaxWidth().clickable {
                                    nav.openFile(FileEntry.from(File(item.path)))
                                }.padding(vertical = 4.dp), maxLines = 2,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (r.duplicatesIncomplete) Text(
                        "Поиск дубликатов ограничен объёмом чтения. Непроверенные файлы не считаются одинаковыми.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Ничего не удаляется автоматически.", modifier = Modifier.padding(bottom = 24.dp))
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
    FileKind.OTHER -> "Другие"
}
