package io.personal.filemanager.ui.sections

import io.personal.filemanager.ui.components.rememberListSearch
import io.personal.filemanager.ui.components.ListSearchField
import io.personal.filemanager.ui.components.ListSearchButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.core.FileTypes
import io.personal.filemanager.core.formatShortDate
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.ops.TrashItem
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.components.EmptyState
import io.personal.filemanager.ui.components.KindBadge
import io.personal.filemanager.ui.components.LoadingBox
import io.personal.filemanager.ui.components.ScreenTopBar

/** Корзина приложения: восстановление на исходное место или окончательное удаление после подтверждения. */
@Composable
fun TrashScreen() {
    val navigator = LocalNavigator.current
    val operations = appContainer().operations
    val version by operations.version.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<TrashItem>?>(null) }
    var purgeTargets by remember { mutableStateOf<List<TrashItem>?>(null) }

    LaunchedEffect(version) { items = operations.trashItems() }

    Column(Modifier.fillMaxSize()) {
        val search = rememberListSearch()
        val all = items
        val list = all?.filter { search.matches(it.meta.name, it.meta.originalPath) }
        ScreenTopBar(
            title = "Корзина",
            subtitle = all?.let { "Объектов: ${it.size} • ${formatSize(it.sumOf { i -> i.meta.size })}" },
            onBack = { navigator.back() },
            actions = {
                if (!all.isNullOrEmpty()) {
                    ListSearchButton(search)
                    TextButton(onClick = { purgeTargets = all }) { Text("Очистить") }
                }
            },
        )
        ListSearchField(search)
        when {
            list == null -> LoadingBox()
            !all.isNullOrEmpty() && list.isEmpty() -> EmptyState(Icons.Filled.DeleteOutline, "Ничего не найдено", "Измените запрос.")
            list.isEmpty() -> EmptyState(
                Icons.Filled.DeleteOutline,
                "Корзина пуста",
                "Удалённые через приложение файлы попадают сюда и занимают место, пока корзину не очистить.",
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(
                        "Файлы лежат в скрытой папке .FileManagerTrash того же носителя. Восстановление не перезаписывает " +
                            "существующие файлы: при совпадении имени добавляется «(1)».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                items(list, key = { it.id }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KindBadge(FileTypes.kindOf(item.meta.name, item.meta.isDirectory), 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.meta.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${formatShortDate(item.meta.deletedAt)} • ${formatSize(item.meta.size)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                item.meta.originalPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { operations.restore(listOf(item)) }) {
                            Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Восстановить «${item.meta.name}»")
                        }
                        IconButton(onClick = { purgeTargets = listOf(item) }) {
                            Icon(Icons.Filled.DeleteForever, contentDescription = "Удалить навсегда «${item.meta.name}»")
                        }
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }

    purgeTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { purgeTargets = null },
            title = { Text("Удалить навсегда?") },
            text = {
                Text(
                    (if (targets.size == 1) "«${targets.first().meta.name}»" else "Объектов: ${targets.size}") +
                        " будет удалено без возможности восстановления.",
                )
            },
            confirmButton = {
                TextButton(onClick = { purgeTargets = null; operations.purge(targets) }) {
                    Text("Удалить навсегда", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { purgeTargets = null }) { Text("Отмена") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
}
