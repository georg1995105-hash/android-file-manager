package io.personal.filemanager.ui.sections

import io.personal.filemanager.ui.appSharedViewModel
import kotlinx.coroutines.flow.first
import io.personal.filemanager.ui.components.rememberListSearch
import io.personal.filemanager.ui.components.ListSearchState
import io.personal.filemanager.ui.components.ListSearchField
import io.personal.filemanager.ui.components.ListSearchButton
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
import androidx.compose.material.icons.filled.MoreVert
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
import io.personal.filemanager.ui.LocalCreateDialog
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

    /** Сортировка раздела хранится в настройках: дата добавления / изменения, размер, имя. */
    val sort: StateFlow<RecentSort> = container.settings.mediaSort(category.name).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        if (category == MediaCategory.RECENT) RecentSort.ADDED else RecentSort.MODIFIED,
    )

    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            if (!container.storage.hasAllFilesAccess()) {
                _state.update { it.copy(loading = false, entries = emptyList()) }
                return@launch
            }
            val sort = MediaSort.valueOf(container.settings.mediaSort(category.name).first().name)
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

    fun setSort(sort: RecentSort) {
        viewModelScope.launch {
            container.settings.setMediaSort(category.name, sort)
            refresh()
        }
    }
}

/** Экран раздела из индекса Android: Новые, Фото, Видео, Документы, Архивы. */
@Composable
fun MediaListScreen(section: SectionId, category: MediaCategory) {
    val navigator = LocalNavigator.current
    val createFile = LocalCreateDialog.current
    val hasAccess = rememberAllFilesAccess()
    val vm = mediaListViewModel(category)
    val state by vm.state.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }
    val view = rememberFileView(category.name)
    val search = rememberListSearch()

    val subtitle = when {
        !hasAccess || state.loading -> null
        else -> "${sort.title} • ${pluralRu(state.entries.size, "файл", "файла", "файлов")}" +
            if (state.entries.size >= MediaRepository.DEFAULT_LIMIT) " (первые)" else ""
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = section.title,
            subtitle = subtitle,
            onBack = { navigator.back() },
            actions = {
                ListSearchButton(search)
                ViewModeButton(view)
                Row {
                    IconButton(onClick = { sortMenu = true }) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Filled.Sort, contentDescription = "Сортировка")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        RecentSort.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text((if (s == sort) "✓ " else "") + s.title) },
                                onClick = { sortMenu = false; vm.setSort(s) },
                            )
                        }
                    }
                    IconButton(onClick = { actionsMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Меню")
                    }
                    DropdownMenu(expanded = actionsMenu, onDismissRequest = { actionsMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Создать файл или папку") },
                            onClick = { actionsMenu = false; createFile() },
                        )
                    }
                }
            },
        )
        ListSearchField(search)
        MediaListBody(category, vm, search)
    }
}

@Composable
private fun mediaListViewModel(category: MediaCategory): MediaListViewModel =
    appSharedViewModel(key = "media-list-${category.name}") { MediaListViewModel(it, category) }

/** Содержимое списка без заголовка (используется и во вкладке «APK-файлы»). */
@Composable
fun MediaListBody(
    category: MediaCategory,
    vm: MediaListViewModel = mediaListViewModel(category),
    search: ListSearchState? = null,
) {
    val view = rememberFileView(category.name)
    val hasAccess = rememberAllFilesAccess()
    val state by vm.state.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val isRecent = category == MediaCategory.RECENT
    val shown = if (search == null) state.entries else state.entries.filter { search.matches(it.name) }

    RefreshWhileVisible(vm.changes) { vm.refresh() }
    val opsVersion by appContainer().operations.version.collectAsStateWithLifecycle()
    LaunchedEffect(opsVersion) { if (opsVersion > 0) vm.refresh() }

    when {
        !hasAccess -> AllFilesAccessRequired()
        state.loading -> LoadingBox()
        state.error != null -> EmptyState(Icons.Filled.FolderOpen, "Ошибка", state.error.orEmpty())
        state.entries.isEmpty() -> EmptyState(Icons.Filled.FolderOpen, "Пока пусто", emptyText(category))
        shown.isEmpty() -> EmptyState(Icons.Filled.FolderOpen, "Ничего не найдено", "Измените запрос.")
        else -> FilesPane(
            entries = shown,
            view = view,
            // При сортировке по появлению под файлом дата добавления в память; иначе — дата изменения.
            dateShown = if (sort == RecentSort.ADDED) DateShown.ADDED else DateShown.MODIFIED,
            headerText = if (!isRecent || sort == RecentSort.SIZE || sort == RecentSort.NAME) {
                null
            } else if (sort == RecentSort.ADDED) {
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
    MediaCategory.AUDIO -> "Аудиофайлы не найдены."
    MediaCategory.DOCUMENTS -> "Документы (PDF, Office, TXT, MD и др.) не найдены."
    MediaCategory.ARCHIVES -> "Архивы (ZIP, 7z, RAR, TAR и др.) не найдены."
    MediaCategory.APKS -> "APK-файлы не найдены."
    MediaCategory.DOWNLOADS -> "В «Загрузках» пусто."
}
