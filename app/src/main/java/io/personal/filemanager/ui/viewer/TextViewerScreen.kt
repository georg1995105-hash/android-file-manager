package io.personal.filemanager.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.TextLoader
import io.personal.filemanager.data.TextResult
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.components.EmptyState
import io.personal.filemanager.ui.components.LoadingBox
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.components.card
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val LINES_PER_CHUNK = 120
private const val MAX_COPY_CHARS = 500_000

class TextViewerViewModel(private val path: String) : ViewModel() {
    private val _result = MutableStateFlow<TextResult?>(null)
    val result: StateFlow<TextResult?> = _result.asStateFlow()

    /** Текст разбит на блоки строк, чтобы большой файл не верстался одним гигантским элементом. */
    var chunks: List<String> = emptyList()
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val r = TextLoader.load(File(path))
            if (r is TextResult.Ok) chunks = r.text.lines().chunked(LINES_PER_CHUNK) { it.joinToString("\n") }
            _result.value = r
        }
    }
}

private val monospaceExtensions = setOf("json", "yaml", "yml", "xml", "log", "csv", "tsv", "ini", "conf", "cfg", "toml", "kt", "kts", "java", "py", "js", "ts", "sh", "bash", "sql", "gradle", "properties", "env")

/** Встроенный просмотр текста: кодировка UTF-8/UTF-16/Windows-1251, лимит размера, выделение и копирование. */
@Composable
fun TextViewerScreen(path: String) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val vm = appViewModel(key = "text-$path") { TextViewerViewModel(path) }
    val result by vm.result.collectAsStateWithLifecycle()
    val file = remember(path) { File(path) }
    val entry = remember(path) { FileEntry.from(file) }
    val mono = entry.extension in monospaceExtensions

    Column(Modifier.fillMaxSize()) {
        val ok = result as? TextResult.Ok
        ScreenTopBar(
            title = file.name,
            subtitle = ok?.let { "${it.charsetName} • ${formatSize(it.totalBytes)}" + if (it.truncated) " • показано начало" else "" },
            onBack = { navigator.back() },
        )
        Box(Modifier.weight(1f)) {
            when (val r = result) {
                null -> LoadingBox()
                is TextResult.Error -> EmptyState(Icons.Filled.ErrorOutline, "Не удалось открыть", r.message)
                TextResult.Binary -> EmptyState(
                    Icons.Filled.ErrorOutline,
                    "Похоже на двоичный файл",
                    "Его нельзя показать как текст. Откройте в подходящем приложении или отправьте.",
                    action = { Button(onClick = { FileActions.openExternally(context, entry) }) { Text("Открыть в другом приложении") } },
                )
                is TextResult.Ok -> TextBody(vm.chunks, r, mono)
            }
        }
        // Действия — внизу, в зоне большого пальца.
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .card(RoundedCornerShape(18.dp))
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (ok != null) {
                TextButton(onClick = {
                    val text = ok.text.take(MAX_COPY_CHARS)
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(file.name, text))
                    if (ok.text.length > MAX_COPY_CHARS) {
                        FileActions.toast(context, "Скопированы первые ${MAX_COPY_CHARS / 1000} тыс. символов")
                    }
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Копировать всё")
                }
            }
            TextButton(onClick = { FileActions.share(context, listOf(entry)) }) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Поделиться")
            }
            TextButton(onClick = { FileActions.openExternally(context, entry) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Открыть в…")
            }
        }
    }
}

@Composable
private fun TextBody(chunks: List<String>, result: TextResult.Ok, mono: Boolean) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (result.truncated) {
            item {
                Text(
                    "Файл большой: показаны первые ${formatSize(TextLoader.DEFAULT_LIMIT_BYTES.toLong())}. " +
                        "Полностью — через «Открыть в…».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        items(chunks) { chunk ->
            SelectionContainer {
                Text(
                    chunk,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                        fontSize = if (mono) 13.sp else 15.sp,
                    ),
                )
            }
        }
    }
}
