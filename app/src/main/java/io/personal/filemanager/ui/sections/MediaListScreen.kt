package io.personal.filemanager.ui.sections

import io.personal.filemanager.ui.components.rememberFileView
import io.personal.filemanager.ui.components.ViewModeButton
import io.personal.filemanager.ui.components.FilesPane
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.AppContainer
import io.personal.filemanager.core.SectionId
import io.personal.filemanager.core.pluralRu
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.MediaCategory
import io.personal.filemanager.data.MediaRepository
import io.personal.filemanager.data.MediaSort
import io.personal.filemanager.data.settings.RecentSort
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.components.AllFilesAccessRequired
import io.personal.filemanager.ui.components.DateShown
import io.personal.filemanager.ui.components.EmptyState
import io.personal.filemanager.ui.components.LoadingBox
import io.personal.filemanager.ui.components.RefreshWhileVisible
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.components.rememberAllFilesAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MediaListState(
    val loading: Boolean = true,
    val entries: List<FileEntry> = emptyList(),
    val error: String? = null,
)

class MediaListViewModel(private val container: AppContainer, private val category: MediaCategory) : ViewModel() {
    private val _state = MutableStateFlow(MediaListState())
    val state: StateFlow<MediaListState> = _state.asStateFlow()
    val changes = container.media.changes()

    /** Сортировка «Новых» хранится в настройках (дата добавления / изменения). */
    val recentSort: StateFlow<RecentSort> =
        container.settings.recentSort.stateIn(viewModelScope, SharingStarted.Eagerly, RecentSort.ADDED)

    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            if (!container.storage.hasAllFilesAccess()) {
                _state.update { it.copy(loading = false, entries = emptyList()) }
                return@launch
            }
            val sort = if (category == MediaCategory.RECENT && container.settings.recentSortNow() == RecentSort.MODIFIED) {
                MediaSort.MODIFIED
            } else if (category == MediaCategory.RECENT) {
                MediaSort.ADDED
            } else {
                MediaSort.MODIFIED
            }
            val result = runCatching { container.media.list(category, sort) }
            _state.update {
                MediaListState(
                    loading = false,
                    entries = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.let { e -> "Не удалось получить список: ${e.message ?: e.javaClass.simpleName}" },
                )
            }
        }
    }

    fun setRecentSort(sort: RecentSort) {
        viewModelScope.launch {
            container.settings.setRecentSort(sort)
            refresh()
        }
    }
}

/** Экран раздела из индекса Android: Новые, Фото, Видео, Документы, Архивы. */
@Composable
fun MediaListScreen(section: SectionId, category: MediaCategory) {
    val navigator = LocalNavigator.current
    val hasAccess = rememberAllFilesAccess()
    val vm = mediaListViewModel(category)
    val state by vm.state.collectAsStateWithLifecycle()
    val recentSort by vm.recentSort.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    val view = rememberFileView(category.name)

    val isRecent = category == MediaCategory.RECENT
    val subtitle = when {
        !hasAccess || state.loading -> null
        isRecent -> "${recentSort.title} • ${pluralRu(state.entries.size, "файл", "файла", "файлов")}"
        else -> pluralRu(state.entries.size, "файл", "файла", "файлов") +
            if (state.entries.size >= MediaRepository.DEFAULT_LIMIT) " (последние)" else ""
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = section.title,
            subtitle = subtitle,
            onBack = { navigator.back() },
            actions = {
                IconButton(onClick = { navigator.openSearch() }) {
                    Icon(Icons.Filled.Search, contentDescription = "Поиск")
                }
                ViewModeButton(view)
                if (isRecent) {
                    Row {
                        IconButton(onClick = { sortMenu = true }) {
                            @Suppress("DEPRECATION")
                            Icon(Icons.Filled.Sort, contentDescription = "Сортировка")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            RecentSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text((if (s == recentSort) "✓ " else "") + s.title) },
                                    onClick = { sortMenu = false; vm.setRecentSort(s) },
                                )
                            }
                        }
                    }
                }
            },
        )
        MediaListBody(category, vm)
    }
}

@Composable
private fun mediaListViewModel(category: MediaCategory): MediaListViewModel =
    appViewModel(key = "media-${category.name}") { MediaListViewModel(it, category) }

/** Содержимое списка без заголовка (используется и во вкладке «APK-файлы»). */
@Composable
fun MediaListBody(category: MediaCategory, vm: MediaListViewModel = mediaListViewModel(category)) {
    val view = rememberFileView(category.name)
    val hasAccess = rememberAllFilesAccess()
    val state by vm.state.collectAsStateWithLifecycle()
    val recentSort by vm.recentSort.collectAsStateWithLifecycle()
    val isRecent = category == MediaCategory.RECENT

    RefreshWhileVisible(vm.changes) { vm.refresh() }
    val opsVersion by appContainer().operations.version.collectAsStateWithLifecycle()
    LaunchedEffect(opsVersion) { if (opsVersion > 0) vm.refresh() }

    when {
        !hasAccess -> AllFilesAccessRequired()
        state.loading -> LoadingBox()
        state.error != null -> EmptyState(Icons.Filled.FolderOpen, "Ошибка", state.error.orEmpty())
        state.entries.isEmpty() -> EmptyState(Icons.Filled.FolderOpen, "Пока пусто", emptyText(category))
        else -> FilesPane(
            entries = state.entries,
            view = view,
            // «Новые» по дате появления — под файлом дата добавления в память; иначе — дата изменения.
            dateShown = if (isRecent && recentSort == RecentSort.ADDED) DateShown.ADDED else DateShown.MODIFIED,
            headerText = if (!isRecent) {
                null
            } else if (recentSort == RecentSort.ADDED) {
                "Свежие сверху по дате появления файла в памяти телефона. " +
                    "Android не хранит точную дату скачивания для каждого файла."
            } else {
                "Свежие сверху по дате последнего изменения файла."
            },
        )
    }
}

private fun emptyText(category: MediaCategory) = when (category) {
    MediaCategory.RECENT -> "Новых файлов не найдено."
    MediaCategory.PHOTOS -> "Фотографии не найдены."
    MediaCategory.VIDEOS -> "Видео не найдены."
    MediaCategory.DOCUMENTS -> "Документы (PDF, Office, TXT, MD и др.) не найдены."
    MediaCategory.ARCHIVES -> "Архивы (ZIP, 7z, RAR, TAR и др.) не найдены."
    MediaCategory.APKS -> "APK-файлы не найдены."
    MediaCategory.DOWNLOADS -> "В «Загрузках» пусто."
}
