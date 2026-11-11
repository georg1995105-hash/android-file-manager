package io.personal.filemanager.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.TextFileEditor
import io.personal.filemanager.data.TextLoader
import io.personal.filemanager.data.TextResult
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.components.EmptyState
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

private const val LINES_PER_CHUNK = 120
/** Вставка по позиции курсора с заменой выделения; не превращает текст в одну строку. */
internal fun insertAtSelection(value: TextFieldValue, inserted: String): TextFieldValue {
    val begin = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(begin, value.text.length)
    val result = buildString(value.text.length - (end - begin) + inserted.length) {
        append(value.text, 0, begin)
        append(inserted)
        append(value.text, end, value.text.length)
    }
    return TextFieldValue(result, selection = TextRange(begin + inserted.length))
}

class TextViewerViewModel(private val path: String) : ViewModel() {
    private val _result = MutableStateFlow<TextResult?>(null)
    val result: StateFlow<TextResult?> = _result.asStateFlow()

    var chunks: List<String> = emptyList()
        private set
    var editing by mutableStateOf(false)
        private set
    var content by mutableStateOf(TextFieldValue(""))
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private var savedText = ""
    private var snapshot: TextFileEditor.Snapshot? = null
    val modified: Boolean get() = content.text != savedText
    val canEdit: Boolean get() = snapshot != null && (_result.value as? TextResult.Ok)?.truncated == false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(path)
            val r = TextLoader.load(file)
            val original = (r as? TextResult.Ok)?.text
            val snap = if (r is TextResult.Ok && !r.truncated) TextFileEditor.snapshot(file, r.charsetName) else null
            val blocks = original?.lines()?.chunked(LINES_PER_CHUNK) { it.joinToString("\n") }.orEmpty()
            withContext(Dispatchers.Main) {
                chunks = blocks
                savedText = original.orEmpty()
                content = TextFieldValue(savedText)
                snapshot = snap
                _result.value = r
            }
        }
    }

    fun edit() {
        if (canEdit) { editing = true; message = null }
    }

    fun change(value: TextFieldValue) {
        // Лимит по символам — дешёвый верхний барьер во время печати.
        // Точное ограничение в байтах проверяется при вставке и сохранении.
        if (value.text.length > TextFileEditor.MAX_EDIT_BYTES) {
            message = "Максимальный размер редактируемого файла — 4 МБ"
            return
        }
        content = value
        message = null
    }

    fun paste(text: String) {
        if (!canEdit || busy || !editing) return
        val candidate = insertAtSelection(content, text)
        val charset = snapshot?.charsetName ?: return
        val problem = TextFileEditor.validationError(candidate.text, charset)
        if (problem != null) { message = problem; return }
        content = candidate
        message = null
    }

    fun showMessage(value: String) { message = value }

    fun discard() {
        content = TextFieldValue(savedText)
        editing = false
        message = null
    }

    fun formatJson() {
        val formatted = JsonPrettyPrinter.format(content.text)
        if (formatted.isSuccess) {
            val pretty = formatted.getOrThrow()
            val charset = snapshot?.charsetName ?: return
            val problem = TextFileEditor.validationError(pretty, charset)
            if (problem != null) {
                message = "Не удалось форматировать: $problem"
                return
            }
            content = content.copy(text = pretty, selection = TextRange(pretty.length))
            message = "JSON отформатирован. Для записи нажмите «Сохранить»."
        } else {
            message = "Некорректный JSON: проверьте скобки, кавычки и запятые"
        }
    }

    fun save(onSaved: () -> Unit) {
        if (busy || !editing) return
        val current = snapshot ?: return
        if (!modified) {
            message = "Изменений нет"
            return
        }
        busy = true
        val textToSave = content.text
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                TextFileEditor.save(File(path), textToSave, current)
            }
            when (outcome) {
                is TextFileEditor.Result.Saved -> {
                    snapshot = outcome.snapshot
                    savedText = textToSave
                    val newResult = TextResult.Ok(
                        text = textToSave,
                        charsetName = outcome.snapshot.charsetName,
                        truncated = false,
                        totalBytes = File(path).length(),
                    )
                    chunks = textToSave.lines().chunked(LINES_PER_CHUNK) { it.joinToString("\n") }
                    _result.value = newResult
                    message = "Сохранено"
                    onSaved()
                }
                TextFileEditor.Result.ChangedExternally ->
                    message = "Файл изменился в другом приложении. Сохранение отменено во избежание потери данных."
                is TextFileEditor.Result.Failure -> message = outcome.message
            }
            busy = false
        }
    }
}

private val monospaceExtensions = setOf(
    "json", "yaml", "yml", "xml", "log", "csv", "tsv", "ini", "conf", "cfg", "toml",
    "kt", "kts", "java", "py", "js", "ts", "sh", "bash", "sql", "gradle", "properties", "env",
)

/** Просмотр и редактирование небольших документов без потери кодировки. */
@Composable
fun TextViewerScreen(path: String, autoEdit: Boolean = false) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val vm = appViewModel(key = "text-$path") { TextViewerViewModel(path) }
    val result by vm.result.collectAsStateWithLifecycle()
    val file = remember(path) { File(path) }
    val extension = file.extension.lowercase()
    val entry = remember(path) { FileEntry.from(file) }
    val mono = extension in monospaceExtensions
    var confirmDiscard by remember { mutableStateOf(false) }
    val syntax = remember(extension) { SyntaxHighlight(extension) }
    val operations = appContainer().operations
    val onSaved = { operations.rescan(listOf(path)); FileActions.toast(context, "Файл сохранён") }

    fun pasteFromClipboard() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val pasted = try {
            if (clip == null || clip.itemCount == 0) null
            else clip.getItemAt(0).coerceToText(context)?.toString()
        } catch (_: Exception) { null }
        if (pasted == null) vm.showMessage("В буфере обмена нет доступного текста")
        else vm.paste(pasted)
    }

    fun copyAll() {
        val displayed = (result as? TextResult.Ok) ?: return
        // Не называем «всем файлом» отрывок, если просмотр большого файла ограничен.
        val contents = if (vm.editing) vm.content.text else displayed.text
        try {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            if (clipboard == null) {
                vm.showMessage("Буфер обмена недоступен")
                return
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(file.name, contents))
            FileActions.toast(
                context,
                if (!vm.editing && displayed.truncated) "Скопирована только отображаемая часть"
                else "Весь текст скопирован",
            )
        } catch (_: Exception) {
            vm.showMessage("Android не смог скопировать такой объём текста. Используй «Поделиться».")
        }
    }
    val editorFocus = remember { FocusRequester() }
    LaunchedEffect(vm.editing) {
        if (vm.editing) editorFocus.requestFocus()
    }

    LaunchedEffect(autoEdit, result) {
        if (autoEdit && vm.canEdit && !vm.editing) vm.edit()
    }

    fun leaveEditor() {
        if (!vm.editing) navigator.back()
        else if (vm.modified) confirmDiscard = true
        else vm.discard()
    }

    BackHandler(enabled = vm.editing) { leaveEditor() }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Несохранённые изменения") },
            text = { Text("Изменения в файле не сохранены. Отменить редактирование?") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; vm.discard() }) {
                    Text("Не сохранять")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Продолжить редактирование") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        val ok = result as? TextResult.Ok
        ScreenTopBar(
            title = file.name,
            subtitle = ok?.let {
                "${it.charsetName} • ${formatSize(it.totalBytes)}" +
                    if (it.truncated) " • показано начало" else if (vm.editing) " • редактор" else ""
            },
            onBack = { leaveEditor() },
            actions = {
                if (vm.editing) {
                    IconButton(onClick = { vm.save(onSaved) }, enabled = !vm.busy && vm.modified) {
                        Icon(Icons.Filled.Save, contentDescription = "Сохранить")
                    }
                } else if (vm.canEdit) {
                    IconButton(onClick = { vm.edit() }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Редактировать")
                    }
                }
            },
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
                is TextResult.Ok -> if (vm.editing) {
                    BasicTextField(
                        value = vm.content,
                        onValueChange = vm::change,
                        modifier = Modifier.fillMaxSize().focusRequester(editorFocus).padding(horizontal = 16.dp, vertical = 12.dp),
                        textStyle = TextStyle(
                            color = AppColors.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                        ),
                        cursorBrush = SolidColor(AppColors.Accent),
                        visualTransformation = syntax,
                    )
                } else {
                    TextBody(vm.chunks, r, mono, syntax)
                }
            }
        }

        if (vm.message != null) {
            Text(
                vm.message.orEmpty(),
                color = if (vm.message == "Сохранено") AppColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .card(RoundedCornerShape(18.dp))
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            if (vm.editing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { pasteFromClipboard() }, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
                        Text("Вставить", fontSize = 12.sp, maxLines = 1)
                    }
                    TextButton(onClick = { copyAll() }, modifier = Modifier.weight(1f)) {
                        Text("Копировать всё", fontSize = 12.sp, maxLines = 1)
                    }
                    TextButton(onClick = { vm.save(onSaved) },
                        enabled = !vm.busy && vm.modified, modifier = Modifier.weight(1f)) {
                        Text("Сохранить", fontSize = 12.sp, maxLines = 1)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    if (extension == "json") {
                        TextButton(onClick = { vm.formatJson() }, enabled = !vm.busy) {
                            Text("Форматировать JSON", fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = { leaveEditor() }, enabled = !vm.busy) {
                        Text("Отмена", fontSize = 12.sp)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (vm.canEdit) {
                        TextButton(onClick = { vm.edit() }, modifier = Modifier.weight(1f)) {
                            Text("Редактировать", fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    if (ok != null) {
                        TextButton(onClick = { copyAll() }, modifier = Modifier.weight(1f)) {
                            Text(if (ok.truncated) "Копировать часть" else "Копировать всё",
                                fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    TextButton(onClick = { FileActions.share(context, listOf(FileEntry.from(file))) },
                        modifier = Modifier.weight(1f)) {
                        Text("Поделиться", fontSize = 12.sp, maxLines = 1)
                    }
                }
                TextButton(onClick = { FileActions.openExternally(context, entry) },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                    Text("Открыть в другом приложении", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TextBody(chunks: List<String>, result: TextResult.Ok, mono: Boolean, syntax: SyntaxHighlight) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
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
                    syntax.filter(AnnotatedString(chunk)).text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                        fontSize = if (mono) 13.sp else 15.sp,
                    ),
                )
            }
        }
    }
}
