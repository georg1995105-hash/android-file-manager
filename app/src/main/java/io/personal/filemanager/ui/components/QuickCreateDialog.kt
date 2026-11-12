package io.personal.filemanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.personal.filemanager.data.CreateFolderResult
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.io.File

private enum class EntryPreset(val label: String, val suggestedName: String, val folder: Boolean = false) {
    DIRECTORY("Папка", "", true),
    TXT("TXT", "Новый файл.txt"),
    MD("Markdown", "README.md"),
    JSON("JSON", "config.json"),
    XML("XML", "config.xml"),
    YAML("YAML", "config.yaml"),
    CONF("Конфиг", "config.conf"),
    OTHER("Другой", ""),
}

/** Один интерфейс для создания файла из папки, главной и сводных разделов. */
@Composable
fun QuickCreateDialog(
    initialFolder: String,
    rootFolder: String,
    downloadsFolder: String,
    onDismiss: () -> Unit,
    onCreate: suspend (folder: String, isFolder: Boolean, filename: String) -> CreateFolderResult,
    onCreated: (FileEntry) -> Unit,
) {
    var preset by remember { mutableStateOf(EntryPreset.TXT) }
    var filename by remember { mutableStateOf(EntryPreset.TXT.suggestedName) }
    var folder by remember(initialFolder) { mutableStateOf(initialFolder) }
    var folderMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val documentsFolder = File(rootFolder, "Documents").absolutePath
    val destinations = listOfNotNull(
        if (initialFolder != downloadsFolder) "Эта папка" to initialFolder else null,
        "Загрузки" to downloadsFolder,
        "Документы" to documentsFolder,
        "Внутренняя память" to rootFolder,
    ).distinctBy { it.second }.filter { File(it.second).isDirectory }
    val destinationLabel = destinations.firstOrNull { it.second == folder }?.first ?: folder

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Создать") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Тип", style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
                EntryPreset.entries.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { item ->
                            val active = item == preset
                            Column(
                                Modifier.weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (active) AppColors.AccentContainer else AppColors.Card)
                                    .border(1.dp, if (active) AppColors.Accent else AppColors.CardBorder, RoundedCornerShape(12.dp))
                                    .clickable {
                                        preset = item
                                        filename = item.suggestedName
                                        error = null
                                    }
                                    .padding(vertical = 10.dp, horizontal = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    if (item.folder) Icons.Filled.Folder
                                    else if (item == EntryPreset.OTHER) Icons.Filled.InsertDriveFile
                                    else Icons.Filled.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = if (active) AppColors.Accent else AppColors.TextSecondary,
                                )
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) AppColors.TextPrimary else AppColors.TextSecondary,
                                    maxLines = 1,
                                )
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                OutlinedTextField(
                    value = filename,
                    onValueChange = { filename = it; error = null },
                    label = { Text(if (preset.folder) "Имя папки" else "Имя файла с расширением") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                )
                Text("Папка назначения", style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
                Box {
                    TextButton(onClick = { folderMenu = true }, enabled = !busy) {
                        Text(destinationLabel)
                        Spacer(Modifier.width(8.dp))
                        Text("▾", color = AppColors.Accent)
                    }
                    DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                        destinations.forEach { (title, target) ->
                            DropdownMenuItem(
                                text = { Text(if (target == folder) "✓ $title" else title) },
                                onClick = { folder = target; folderMenu = false; error = null },
                            )
                        }
                    }
                }
                Text(
                    folder,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val result = onCreate(folder, preset.folder, filename)
                        busy = false
                        when (result) {
                            is CreateFolderResult.Created -> {
                                onDismiss()
                                onCreated(result.entry)
                            }
                            is CreateFolderResult.Invalid -> error = result.reason
                            CreateFolderResult.AlreadyExists -> error = "Файл или папка с таким именем уже существует"
                            CreateFolderResult.Failed -> error = "Не удалось создать. Проверьте доступ и папку назначения."
                        }
                    }
                },
            ) { Text(if (busy) "Создаётся…" else "Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}
