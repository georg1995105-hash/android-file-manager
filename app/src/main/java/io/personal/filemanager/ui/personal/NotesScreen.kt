package io.personal.filemanager.ui.personal

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.AppContainer
import io.personal.filemanager.core.formatShortDate
import io.personal.filemanager.data.db.Note
import io.personal.filemanager.data.ops.FileOperations
import io.personal.filemanager.data.ops.uniqueTarget
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.Routes
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.components.EmptyState
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Список заметок: закреплённые сверху, затем по изменению; поиск — внизу экрана. */
@Composable
fun NotesScreen() {
    val navigator = LocalNavigator.current
    val notes by appContainer().personal.notes.collectAsStateWithLifecycle(initialValue = null)
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        val list = notes
        ScreenTopBar(
            title = "Заметки",
            subtitle = list?.let { "Всего: ${it.size}" },
            onBack = { navigator.back() },
        )
        val filtered = list.orEmpty().filter {
            query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true)
        }
        Column(Modifier.weight(1f)) {
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    Icons.Filled.StickyNote2,
                    "Заметок пока нет",
                    "Заметки хранятся только на телефоне и сохраняются автоматически. Для переноса используйте «Резервные копии».",
                    action = { Button(onClick = { navigator.open(Routes.note(0)) }) { Text("Новая заметка") } },
                )
                filtered.isEmpty() -> EmptyState(Icons.Filled.Search, "Ничего не найдено", "Измените запрос.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(filtered, key = { it.id }) { note ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { navigator.open(Routes.note(note.id)) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (note.pinned) {
                                    Icon(Icons.Filled.PushPin, contentDescription = "Закреплена", tint = AppColors.Accent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(note.displayTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val preview = note.body.lineSequence().filter { it.isNotBlank() }.drop(if (note.title.isBlank()) 1 else 0).firstOrNull()
                            if (preview != null) {
                                Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(formatShortDate(note.updated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (!list.isNullOrEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Поиск в заметках") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Очистить") }
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = AppColors.Card, unfocusedContainerColor = AppColors.Card),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { navigator.open(Routes.note(0)) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Новая заметка", tint = AppColors.Accent)
                }
            }
        }
    }
}

/**
 * Редактор: автосохранение через паузу в наборе, при уходе с экрана и сворачивании.
 * Пустая новая заметка не сохраняется.
 */
@OptIn(FlowPreview::class)
class NoteEditorViewModel(private val container: AppContainer, initialId: Long) : ViewModel() {
    data class State(val loaded: Boolean = false, val note: Note? = null, val saved: Boolean = true, val stored: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var id = initialId
    private var created = System.currentTimeMillis()
    private var saveJob: Job? = null
    var title = ""
        private set
    var body = ""
        private set
    var pinned = false
        private set

    init {
        viewModelScope.launch {
            val existing = if (initialId > 0) container.personal.note(initialId) else null
            existing?.let {
                title = it.title
                body = it.body
                pinned = it.pinned
                created = it.created
            }
            _state.value = State(loaded = true, note = existing, stored = existing != null)
        }
    }

    fun edit(newTitle: String = title, newBody: String = body, newPinned: Boolean = pinned) {
        title = newTitle
        body = newBody
        pinned = newPinned
        _state.value = _state.value.copy(saved = false)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(700)
            saveNow()
        }
    }

    suspend fun saveNow() {
        if (_state.value.saved) return
        if (id == 0L && title.isBlank() && body.isBlank()) return
        id = container.personal.saveNote(Note(id, title, body, created, System.currentTimeMillis(), pinned))
        _state.value = _state.value.copy(saved = true, stored = true)
    }

    fun flush() {
        // Сохранение не должно прерываться уходом с экрана.
        container.appScope.launch { saveNow() }
    }

    suspend fun delete() {
        saveJob?.cancel()
        if (id > 0) container.personal.deleteNote(id)
        id = 0
        title = ""
        body = ""
        _state.value = _state.value.copy(saved = true, stored = false)
    }

    val text: String get() = (if (title.isNotBlank()) "# $title\n\n" else "") + body

    /** Экспорт в .md в «Загрузки/Заметки»; существующие файлы не перезаписываются. */
    suspend fun exportMarkdown(): File = withContext(Dispatchers.IO) {
        saveNow()
        val dir = File(container.storage.downloadsPath, "Заметки").apply { mkdirs() }
        val base = io.personal.filemanager.data.apps.ApkSets.safeFileName(Note(0, title, body, 0, 0).displayTitle)
        val target = File(dir, "$base.md").let { if (it.exists()) uniqueTarget(dir, it.name) else it }
        val tmp = File(dir, "${FileOperations.PARTIAL_PREFIX}${target.name}")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("Не удалось сохранить файл")
        }
        container.operations.rescan(listOf(target.absolutePath))
        target
    }

    override fun onCleared() {
        flush()
    }
}

@Composable
fun NoteEditorScreen(noteId: Long) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val vm = appViewModel(key = "note-$noteId") { NoteEditorViewModel(it, noteId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf<String?>(null) }
    var body by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.loaded) {
        if (state.loaded && title == null) {
            title = vm.title
            body = vm.body
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.flush() }
    DisposableEffect(Unit) { onDispose { vm.flush() } }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTopBar(
            title = if (state.stored) "Заметка" else "Новая заметка",
            subtitle = if (state.saved) "Сохранено" else "Сохранение…",
            onBack = { vm.flush(); navigator.back() },
            actions = {
                IconButton(onClick = { vm.edit(newPinned = !vm.pinned) }) {
                    Icon(Icons.Filled.PushPin, contentDescription = if (vm.pinned) "Открепить" else "Закрепить",
                        tint = if (vm.pinned) AppColors.Accent else AppColors.TextSecondary)
                }
                Row {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Меню") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Поделиться текстом") }, onClick = {
                            menu = false
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.text)
                            context.startActivity(Intent.createChooser(send, "Поделиться заметкой").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        })
                        DropdownMenuItem(text = { Text("Экспорт в .md (Загрузки/Заметки)") }, onClick = {
                            menu = false
                            scope.launch {
                                runCatching { vm.exportMarkdown() }
                                    .onSuccess { FileActions.toast(context, "Сохранено: ${it.name}") }
                                    .onFailure { FileActions.toast(context, it.message ?: "Ошибка экспорта") }
                            }
                        })
                        DropdownMenuItem(
                            text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                }
            },
        )
        if (title == null) return@Column
        val fieldColors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        )
        TextField(
            value = title.orEmpty(),
            onValueChange = { title = it; vm.edit(newTitle = it) },
            placeholder = { Text("Заголовок") },
            textStyle = MaterialTheme.typography.titleLarge,
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = body.orEmpty(),
            onValueChange = { body = it; vm.edit(newBody = it) },
            placeholder = { Text("Текст заметки") },
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = fieldColors,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Spacer(Modifier.navigationBarsPadding())
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить заметку?") },
            text = { Text("Заметку нельзя будет восстановить, если она не сохранена в резервной копии.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        vm.delete()
                        navigator.back()
                    }
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
}
