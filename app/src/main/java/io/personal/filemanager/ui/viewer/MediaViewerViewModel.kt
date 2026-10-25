package io.personal.filemanager.ui.viewer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import io.personal.filemanager.AppContainer
import io.personal.filemanager.core.FileKind
import io.personal.filemanager.core.FileTypes
import io.personal.filemanager.ui.MediaViewerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Просмотр фото и видео с перелистыванием. Живёт в записи навигации: переживает поворот экрана без
 * повторной буферизации видео. Один плеер на все видео списка — воспроизводится только текущее.
 */
@OptIn(UnstableApi::class)
class MediaViewerViewModel(private val container: AppContainer, startPath: String) : ViewModel() {
    private val _items = MutableStateFlow(
        MediaViewerSession.paths.takeIf { startPath in it } ?: listOf(startPath),
    )
    /** Пути фото и видео в порядке исходного списка. */
    val items: StateFlow<List<String>> = _items.asStateFlow()

    /** Ориентация активности до открытия просмотра (восстанавливается при выходе). */
    var originalOrientation: Int? = null

    /** Ориентация, выбранная кнопкой поворота; null — та, в которой открыт просмотр. */
    var lockedOrientation: Int? = null

    private var playerOrNull: ExoPlayer? = null
    val player: ExoPlayer
        get() = playerOrNull ?: ExoPlayer.Builder(container.app)
            .setRenderersFactory(DefaultRenderersFactory(container.app).setEnableDecoderFallback(true))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
            .also {
                it.addListener(listener)
                playerOrNull = it
            }

    private val _video = MutableStateFlow(VideoState())
    val video: StateFlow<VideoState> = _video.asStateFlow()
    private var loadJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _video.value = _video.value.copy(isPlaying = isPlaying)
            if (!isPlaying) savePosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _video.value = _video.value.copy(buffering = playbackState == Player.STATE_BUFFERING)
        }

        override fun onRenderedFirstFrame() {
            _video.value = _video.value.copy(firstFrameShown = true)
        }

        override fun onPlayerError(error: PlaybackException) {
            _video.value = _video.value.copy(error = VideoSupport.describe(error), buffering = false)
        }

        override fun onTracksChanged(tracks: Tracks) {
            val audio = tracks.groups.withIndex().filter { it.value.type == C.TRACK_TYPE_AUDIO }
            val options = audio.mapIndexed { n, (index, group) ->
                val f = group.getTrackFormat(0)
                val label = listOfNotNull(
                    f.label,
                    f.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.forLanguageTag("ru")).ifBlank { it } },
                    f.channelCount.takeIf { it > 0 }?.let { "$it кан." },
                ).joinToString(" • ").ifBlank { "Дорожка ${n + 1}" }
                AudioTrackOption(index, label, group.isSelected)
            }
            val videoGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            val info = videoGroup?.let { g ->
                val f = g.getTrackFormat((0 until g.length).firstOrNull { g.isTrackSelected(it) } ?: 0)
                listOfNotNull(
                    if (f.width > 0) "${f.width}×${f.height}" else null,
                    if (f.frameRate > 0) "%.0f к/с".format(f.frameRate) else null,
                    f.sampleMimeType?.removePrefix("video/")?.uppercase(),
                    f.codecs,
                ).joinToString(" • ")
            }
            _video.value = _video.value.copy(
                hasSubtitles = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT },
                audioTracks = options,
                videoInfo = info,
            )
        }
    }

    /** Страница стала текущей: видео — загрузить и воспроизвести, фото — остановить видео. */
    fun onCurrent(path: String) {
        if (isVideo(path)) showVideo(path) else stopVideo()
    }

    private fun showVideo(path: String) {
        if (_video.value.path == path) return
        savePosition()
        _video.value = VideoState(path = path)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val file = File(path)
            // Чтение папки (поиск субтитров) и позиции — вне UI-потока.
            val subtitles = withContext(Dispatchers.IO) { runCatching { VideoSupport.externalSubtitles(file) }.getOrDefault(emptyList()) }
            val resume = container.playback.get(path)
            if (_video.value.path != path) return@launch
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setSubtitleConfigurations(subtitles)
                .build()
            val p = player
            p.setPlaybackSpeed(1f)
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
            p.setMediaItem(item, resume)
            p.prepare()
            p.playWhenReady = true
            _video.value = _video.value.copy(resumedFromMs = resume)
        }
    }

    fun stopVideo() {
        if (_video.value.path == null) return
        savePosition()
        loadJob?.cancel()
        playerOrNull?.let {
            it.stop()
            it.clearMediaItems()
        }
        _video.value = VideoState()
    }

    fun togglePlay() {
        val p = playerOrNull ?: return
        if (_video.value.error != null) return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekBy(deltaMs: Long) {
        val p = playerOrNull ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, duration))
    }

    fun seekTo(positionMs: Long) {
        playerOrNull?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun setSpeed(speed: Float) {
        playerOrNull?.setPlaybackSpeed(speed)
        _video.value = _video.value.copy(speed = speed)
    }

    fun setSubtitles(on: Boolean) {
        val p = playerOrNull ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
            .build()
        _video.value = _video.value.copy(subtitlesOn = on)
    }

    fun selectAudio(groupIndex: Int) {
        val p = playerOrNull ?: return
        val group = p.currentTracks.groups.getOrNull(groupIndex) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    fun pause() {
        playerOrNull?.pause()
    }

    /** Файл перемещён в корзину: убрать из списка (видео перед этим уже остановлено). */
    fun remove(path: String) {
        _items.value = _items.value - path
        MediaViewerSession.paths = MediaViewerSession.paths - path
    }

    private fun savePosition() {
        val p = playerOrNull ?: return
        val path = _video.value.path ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur == C.TIME_UNSET) return
        container.appScope.launch { container.playback.save(path, pos, dur) }
    }

    override fun onCleared() {
        savePosition()
        playerOrNull?.let {
            it.removeListener(listener)
            it.release()
        }
        playerOrNull = null
    }

    companion object {
        fun isVideo(path: String) = FileTypes.kindOf(File(path).name, false) == FileKind.VIDEO
    }
}
