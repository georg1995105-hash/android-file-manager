package io.personal.filemanager.ui.viewer

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.personal.filemanager.data.settings.ControlConfig
import io.personal.filemanager.data.settings.UiConfig
import io.personal.filemanager.data.settings.ViewerControl

/** Порядок и видимость кнопок нижней панели просмотра. Изменения сохраняются сразу. */
@Composable
fun ViewerControlsDialog(current: List<ControlConfig>, onSave: (List<ControlConfig>) -> Unit, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf(current.filter { ViewerControl.fromKey(it.id) != null }) }
    fun update(new: List<ControlConfig>) {
        list = new
        onSave(new)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Кнопки просмотра") },
        text = {
            LazyColumn {
                item {
                    Text(
                        "Порядок слева направо; «Ещё» всегда в конце. Скрытые действия остаются в меню «Ещё».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                itemsIndexed(list, key = { _, c -> c.id }) { index, c ->
                    val control = ViewerControl.fromKey(c.id) ?: return@itemsIndexed
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = c.visible,
                            onCheckedChange = { on -> update(list.map { if (it.id == c.id) it.copy(visible = on) else it }) },
                        )
                        Text(control.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(enabled = index > 0, onClick = {
                            update(list.toMutableList().apply { add(index - 1, removeAt(index)) })
                        }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Выше: ${control.title}") }
                        IconButton(enabled = index < list.lastIndex, onClick = {
                            update(list.toMutableList().apply { add(index + 1, removeAt(index)) })
                        }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Ниже: ${control.title}") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = { TextButton(onClick = { update(UiConfig.defaultViewerControls()) }) { Text("Сбросить") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}
