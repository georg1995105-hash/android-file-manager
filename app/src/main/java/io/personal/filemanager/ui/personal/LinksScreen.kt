package io.personal.filemanager.ui.personal

import io.personal.filemanager.ui.components.tapClickable
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.core.formatShortDate
import io.personal.filemanager.data.db.Link
import io.personal.filemanager.data.db.LinkRules
import io.personal.filemanager.data.ops.FileOperations
import io.personal.filemanager.data.ops.uniqueTarget
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.components.EmptyState
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Ссылки: название, URL, заметка; поиск, открытие в браузере, копирование, отправка, экспорт. */
@Composable
fun LinksScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val container = appContainer()
    val links by container.personal.links.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Link?>(null) }
    var deleting by remember { mutableStateOf<Link?>(null) }

    fun newLink() = Link(0, "", "", "", 0, 0)

    Column(Modifier.fillMaxSize()) {
        val list = links
        ScreenTopBar(
            title = "Ссылки",
            subtitle = list?.let { "Всего: ${it.size}" },
            onBack = { navigator.back() },
            actions = {
                if (!list.isNullOrEmpty()) {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { exportLinks(container, list) }
                                .onSuccess { FileActions.toast(context, "Экспортировано: Загрузки/${it.name}") }
                                .onFailure { FileActions.toast(context, it.message ?: "Ошибка экспорта") }
                        }
                    }) { Text("Экспорт") }
                }
            },
        )
        val filtered = list.orEmpty().filter {
            query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true) || it.note.contains(query, true)
        }
        Column(Modifier.weight(1f)) {
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    Icons.Filled.Link,
                    "Ссылок пока нет",
                    "Ссылки хранятся только на телефоне. Открываются в установленном браузере; само приложение в интернет не выходит.",
                    action = { Button(onClick = { editing = newLink() }) { Text("Добавить ссылку") } },
                )
                filtered.isEmpty() -> EmptyState(Icons.Filled.Search, "Ничего не найдено", "Измените запрос.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(filtered, key = { it.id }) { link ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .tapClickable { openUrl(context, link.url) }
                                .padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(link.title.ifBlank { link.url }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(link.url, style = MaterialTheme.typography.bodySmall, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (link.note.isNotBlank()) {
                                    Text(link.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Text(formatShortDate(link.updated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            LinkMenu(
                                link = link,
                                onEdit = { editing = link },
                                onDelete = { deleting = link },
                            )
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
                    placeholder = { Text("Поиск ссылок") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Очистить") }
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = AppColors.Card, unfocusedContainerColor = AppColors.Card),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { editing = newLink() }) {
                    Icon(Icons.Filled.Add, contentDescription = "Добавить ссылку", tint = AppColors.Accent)
                }
            }
        }
    }

    editing?.let { link ->
        LinkDialog(
            initial = link,
            onDismiss = { editing = null },
            onSave = { title, url, note ->
                val now = System.currentTimeMillis()
                scope.launch {
                    container.personal.saveLink(link.copy(title = title, url = url, note = note, created = if (link.id == 0L) now else link.created, updated = now))
                }
                editing = null
            },
        )
    }
    deleting?.let { link ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить ссылку?") },
            text = { Text(link.title.ifBlank { link.url }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.personal.deleteLink(link.id) }
                    deleting = null
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
}

@Composable
private fun LinkMenu(link: Link, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Действия") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Открыть в браузере") }, onClick = { open = false; openUrl(context, link.url) })
            DropdownMenuItem(text = { Text("Копировать адрес") }, onClick = {
                open = false
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(link.title, link.url))
                FileActions.toast(context, "Адрес скопирован")
            })
            DropdownMenuItem(text = { Text("Поделиться") }, onClick = {
                open = false
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, link.title)
                    .putExtra(Intent.EXTRA_TEXT, link.url)
                context.startActivity(Intent.createChooser(send, "Поделиться ссылкой").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            DropdownMenuItem(text = { Text("Изменить") }, onClick = { open = false; onEdit() })
            DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onDelete() })
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    val safe = LinkRules.normalize(url) ?: return FileActions.toast(context, "Некорректный адрес")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        FileActions.toast(context, "Нет приложения для открытия ссылок")
    }
}

@Composable
private fun LinkDialog(initial: Link, onDismiss: () -> Unit, onSave: (title: String, url: String, note: String) -> Unit) {
    var title by remember { mutableStateOf(initial.title) }
    var url by remember { mutableStateOf(initial.url) }
    var note by remember { mutableStateOf(initial.note) }
    val normalized = LinkRules.normalize(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Новая ссылка" else "Изменить ссылку") },
        text = {
            Column {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, singleLine = true, label = { Text("Адрес (URL)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = url.isNotBlank() && normalized == null,
                    supportingText = { if (url.isNotBlank() && normalized == null) Text("Нужен адрес http(s)://…") },
                )
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Название") })
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Заметка (необязательно)") }, maxLines = 4)
            }
        },
        confirmButton = {
            TextButton(enabled = normalized != null, onClick = { normalized?.let { onSave(title.trim(), it, note.trim()) } }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

/** Экспорт всех ссылок в Markdown-файл в «Загрузках» (без перезаписи существующих файлов). */
private suspend fun exportLinks(container: io.personal.filemanager.AppContainer, links: List<Link>): File = withContext(Dispatchers.IO) {
    val dir = File(container.storage.downloadsPath)
    val target = File(dir, "Ссылки.md").let { if (it.exists()) uniqueTarget(dir, it.name) else it }
    val text = buildString {
        appendLine("# Ссылки")
        appendLine()
        links.forEach { l ->
            append("- [").append(l.title.ifBlank { l.url }.replace("]", "\\]")).append("](").append(l.url).append(")")
            if (l.note.isNotBlank()) append(" — ").append(l.note.replace('\n', ' '))
            appendLine()
        }
    }
    val tmp = File(dir, "${FileOperations.PARTIAL_PREFIX}${target.name}")
    tmp.writeText(text)
    if (!tmp.renameTo(target)) {
        tmp.delete()
        error("Не удалось сохранить файл")
    }
    container.operations.rescan(listOf(target.absolutePath))
    target
}
