package io.personal.filemanager.ui.ops

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.core.SectionId
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.ops.ConflictPolicy
import io.personal.filemanager.data.ops.FileOperations
import io.personal.filemanager.data.ops.RenameResult
import io.personal.filemanager.data.ops.TransferMode
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.components.card
import kotlinx.coroutines.launch
import java.io.File

/** Расширения хоста (архивы, избранное) подключаются следующими этапами через этот интерфейс. */
interface FileOpsExtensions {
    val supportsArchives: Boolean get() = false
    val supportsFavorites: Boolean get() = false
    fun compress(entries: List<FileEntry>) = Unit
    fun extract(entry: FileEntry) = Unit
    fun openArchive(entry: FileEntry) = Unit
    fun toggleFavorite(entry: FileEntry) = Unit
    fun isFavorite(path: String): Boolean = false
}

/**
 * Хост файловых операций: предоставляет [LocalFileOps], показывает диалоги и нижнюю панель
 * «Вставить сюда» / прогресс операции. [bottomInset] — высота нижней навигации над панелью.
 */
@Composable
fun FileOpsHost(
    bottomInset: Dp,
    extensions: FileOpsExtensions = object : FileOpsExtensions {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val operations = appContainer().operations
    val clipboard by operations.clipboard.collectAsStateWithLifecycle()
    val running by operations.running.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var currentFolder by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTargets by remember { mutableStateOf<List<FileEntry>?>(null) }
    var permanentTargets by remember { mutableStateOf<List<FileEntry>?>(null) }
    var conflict by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    LaunchedEffect(operations) {
        operations.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    val controller = remember(extensions) {
        object : FileOpsController {
            override val supportsArchives get() = extensions.supportsArchives
            override val supportsFavorites get() = extensions.supportsFavorites

            private fun toClipboard(entries: List<FileEntry>, mode: TransferMode) {
                if (entries.isEmpty()) return
                operations.setClipboard(entries.map { it.path }, mode)
                if (currentFolder == null) {
                    Toast.makeText(context, "Откройте папку назначения и нажмите «Вставить сюда»", Toast.LENGTH_LONG).show()
                }
            }

            override fun copy(entries: List<FileEntry>) = toClipboard(entries, TransferMode.COPY)
            override fun move(entries: List<FileEntry>) = toClipboard(entries, TransferMode.MOVE)
            override fun rename(entry: FileEntry) { renameTarget = entry }
            override fun delete(entries: List<FileEntry>) { if (entries.isNotEmpty()) deleteTargets = entries }
            override fun compress(entries: List<FileEntry>) = extensions.compress(entries)
            override fun extract(entry: FileEntry) = extensions.extract(entry)
            override fun openArchive(entry: FileEntry) = extensions.openArchive(entry)
            override fun toggleFavorite(entry: FileEntry) = extensions.toggleFavorite(entry)
            override fun isFavorite(path: String) = extensions.isFavorite(path)
            override fun setCurrentFolder(path: String?) { currentFolder = path }
        }
    }

    fun paste(dest: String) {
        val clip = clipboard ?: return
        val names = operations.conflicts(clip.paths, File(dest))
        if (names.isEmpty()) operations.paste(File(dest), ConflictPolicy.SKIP) else conflict = dest to names
    }

    CompositionLocalProvider(LocalFileOps provides controller) {
        Box(Modifier.fillMaxSize()) {
            content()
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInset)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                running?.let { op ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .card(RoundedCornerShape(18.dp))
                            .padding(14.dp),
                    ) {
                        val p = op.progress
                        Text(
                            op.title + (p?.current?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (p != null && p.totalBytes > 0) {
                            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "${formatSize(p.doneBytes)} из ${formatSize(p.totalBytes)} • ${p.doneItems}/${p.totalItems}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (op.cancellable) {
                            TextButton(onClick = { operations.cancel() }, modifier = Modifier.align(Alignment.End)) {
                                Text("Отмена")
                            }
                        }
                    }
                }
                val clip = clipboard
                if (clip != null && running == null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .card(RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${clip.mode.verb}: ${clip.paths.size} шт.",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { operations.clearClipboard() }) { Text("Отмена") }
                        val folder = currentFolder
                        if (folder != null) {
                            Button(onClick = { paste(folder) }) { Text("Вставить сюда") }
                        } else {
                            Button(onClick = { navigator.openSection(SectionId.FILES) }) { Text("Выбрать папку") }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { entry ->
        RenameDialog(
            entry = entry,
            onDismiss = { renameTarget = null },
            onRename = { name ->
                when (val r = operations.rename(entry.path, name)) {
                    is RenameResult.Ok -> null
                    is RenameResult.Error -> r.message
                }
            },
        )
    }

    deleteTargets?.let { targets ->
        val trashable = targets.all { FileOperations.defaultVolumeRoot(File(it.path)) != null && !operations.trash.isInTrash(File(it.path)) }
        AlertDialog(
            onDismissRequest = { deleteTargets = null },
            title = { Text(if (trashable) "Переместить в корзину?" else "Удалить безвозвратно?") },
            text = {
                Text(
                    describe(targets) + "\n\n" + if (trashable) {
                        "Восстановить можно в «Корзине» (меню ⋮ на главной)."
                    } else {
                        "Для этого места корзина недоступна — удаление нельзя будет отменить."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTargets = null
                    if (trashable) operations.moveToTrash(targets.map { it.path }) else permanentTargets = targets
                }) { Text(if (trashable) "В корзину" else "Продолжить…") }
            },
            dismissButton = {
                Row {
                    if (trashable) {
                        TextButton(onClick = { deleteTargets = null; permanentTargets = targets }) {
                            Text("Удалить навсегда…", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { deleteTargets = null }) { Text("Отмена") }
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }

    permanentTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { permanentTargets = null },
            title = { Text("Удалить без возможности восстановления?") },
            text = { Text(describe(targets) + "\n\nЭто действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    permanentTargets = null
                    operations.deletePermanently(targets.map { it.path })
                }) { Text("Удалить навсегда", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { permanentTargets = null }) { Text("Отмена") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }

    conflict?.let { (dest, names) ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("Такие имена уже есть") },
            text = {
                Text(
                    names.take(5).joinToString("\n") { "• $it" } +
                        (if (names.size > 5) "\n…и ещё ${names.size - 5}" else "") +
                        "\n\n«Заменить» отправит существующие объекты в корзину, а не удалит их.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { conflict = null; operations.paste(File(dest), ConflictPolicy.RENAME) }) {
                        Text("Сохранить оба (переименовать)")
                    }
                    TextButton(onClick = { conflict = null; operations.paste(File(dest), ConflictPolicy.SKIP) }) {
                        Text("Пропустить совпадающие")
                    }
                    TextButton(onClick = { conflict = null; operations.paste(File(dest), ConflictPolicy.REPLACE) }) {
                        Text("Заменить", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { conflict = null }) { Text("Отмена") }
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
}

private fun describe(targets: List<FileEntry>): String =
    if (targets.size == 1) "«${targets.first().name}»" else "Объектов: ${targets.size} (${targets.take(3).joinToString { it.name }}${if (targets.size > 3) "…" else ""})"

@Composable
private fun RenameDialog(entry: FileEntry, onDismiss: () -> Unit, onRename: suspend (String) -> String?) {
    val baseEnd = if (entry.isDirectory) entry.name.length else entry.name.lastIndexOf('.').takeIf { it > 0 } ?: entry.name.length
    var value by remember { mutableStateOf(TextFieldValue(entry.name, TextRange(0, baseEnd))) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; error = null },
                singleLine = true,
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
            )
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val err = onRename(value.text)
                    busy = false
                    if (err == null) onDismiss() else error = err
                }
            }) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}
