package io.personal.filemanager.ui.personal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.core.FileTypes
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.db.Favorite
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.Routes
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.components.EmptyState
import io.personal.filemanager.ui.components.KindBadge
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Избранные файлы и папки. Хранится путь; если объект удалён или перемещён не нашим приложением,
 * он показывается как недоступный, и его можно убрать из избранного.
 */
@Composable
fun FavoritesScreen() {
    val navigator = LocalNavigator.current
    val container = appContainer()
    val favorites by container.personal.favorites.collectAsStateWithLifecycle(initialValue = null)
    val opsVersion by container.operations.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val existing by produceState<Set<String>>(emptySet(), favorites, opsVersion) {
        value = withContext(Dispatchers.IO) { favorites.orEmpty().filter { File(it.path).exists() }.map { it.path }.toSet() }
    }

    Column(Modifier.fillMaxSize()) {
        val list = favorites
        ScreenTopBar(title = "Избранное", subtitle = list?.let { "Всего: ${it.size}" }, onBack = { navigator.back() })
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(
                Icons.Filled.StarBorder,
                "В избранном пусто",
                "Добавляйте файлы и папки через меню «⋮» → «В избранное». Хранятся только пути, а не копии файлов.",
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(list, key = { it.id }) { fav ->
                    val available = fav.path in existing
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = available) { open(fav, navigator) }
                            .padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KindBadge(FileTypes.kindOf(fav.name, fav.isDirectory), 44.dp, Modifier.alpha(if (available) 1f else 0.4f))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fav.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (available) fav.path else "Недоступно: файл удалён, перемещён или носитель отключён",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { scope.launch { container.personal.removeFavorite(fav.id) } }) {
                            Icon(Icons.Filled.Star, contentDescription = "Убрать «${fav.name}» из избранного", tint = AppColors.Accent)
                        }
                    }
                }
            }
        }
    }
}

private fun open(fav: Favorite, navigator: io.personal.filemanager.ui.AppNavigator) {
    val file = File(fav.path)
    if (file.isDirectory) navigator.open(Routes.browser(fav.path)) else navigator.openFile(FileEntry.from(file))
}
