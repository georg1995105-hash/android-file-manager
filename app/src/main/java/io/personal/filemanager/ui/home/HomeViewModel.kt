package io.personal.filemanager.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.AppContainer
import io.personal.filemanager.core.SectionId
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.core.formatSizeAndCount
import io.personal.filemanager.data.CategoryStats
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.MediaCategory
import io.personal.filemanager.data.MediaSort
import io.personal.filemanager.data.StorageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val storage: StorageInfo? = null,
    val hasAccess: Boolean = true,
    /** Подписи плиток «1,2 ГБ (282)». Нет записи — подпись не показывается. */
    val stats: Map<SectionId, String> = emptyMap(),
    val recent: List<FileEntry> = emptyList(),
    val recentLoaded: Boolean = false,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    val changes = container.media.changes()

    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            val access = container.storage.hasAllFilesAccess()
            val storage = runCatching { container.storage.internalStorage() }.getOrNull()
            _state.update { it.copy(storage = storage, hasAccess = access) }
            if (!access) {
                _state.update { it.copy(stats = emptyMap(), recent = emptyList(), recentLoaded = true) }
                return@launch
            }
            val recent = runCatching { container.media.list(MediaCategory.RECENT, MediaSort.ADDED, limit = 16).take(4) }
                .getOrDefault(emptyList())
            _state.update { it.copy(recent = recent, recentLoaded = true) }

            val weekAgo = System.currentTimeMillis() / 1000 - 7 * 24 * 3600
            val stats = coroutineScope {
                val media = listOf(
                    SectionId.DOWNLOADS to MediaCategory.DOWNLOADS,
                    SectionId.PHOTOS to MediaCategory.PHOTOS,
                    SectionId.VIDEOS to MediaCategory.VIDEOS,
                    SectionId.DOCUMENTS to MediaCategory.DOCUMENTS,
                    SectionId.ARCHIVES to MediaCategory.ARCHIVES,
                ).map { (id, cat) -> async { id to runCatching { container.media.stats(cat) }.getOrNull() } }
                val recentStats = async { SectionId.RECENT to runCatching { container.media.stats(MediaCategory.RECENT, weekAgo) }.getOrNull() }
                val appsStats = async {
                    SectionId.APPS to runCatching {
                        val apps = container.apps.launchableApps()
                        CategoryStats(apps.sumOf { it.sizeBytes }, apps.size)
                    }.getOrNull()
                }
                (media + recentStats + appsStats).awaitAll()
            }
            val texts = buildMap {
                stats.forEach { (id, s) -> if (s != null) put(id, formatSizeAndCount(s.totalBytes, s.count)) }
                storage?.let { put(SectionId.FILES, "занято ${formatSize(it.usedBytes)}") }
            }
            _state.update { it.copy(stats = texts) }
        }
    }
}
