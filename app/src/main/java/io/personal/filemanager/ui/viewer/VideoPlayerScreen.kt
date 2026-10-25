package io.personal.filemanager.ui.viewer

import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.personal.filemanager.AppContainer
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Шаг перемотки двойным касанием — ровно 10 секунд (ТЗ §4). */
const val SEEK_STEP_MS = 10_000L

data class AudioTrackOption(val groupIndex: Int, val label: String, val selected: Boolean)

data class VideoState(
    val isPlaying: Boolean = false,
    val buffering: Boolean = true,
    val error: String? = null,
    val hasSubtitles: Boolean = false,
    val subtitlesOn: Boolean = true,
    val audioTracks: List<AudioTrackOption> = emptyList(),
    val speed: Float = 1f,
    val videoInfo: String? = null,
    val resumedFromMs: Long = 0L,
)

/**
 * Плеер живёт во ViewModel: переживает ручной поворот экрана без повторной буферизации,
 * освобождается при уходе с экрана (onCleared).
 */
@OptIn(UnstableApi::class)
class VideoPlayerViewModel(private val container: AppContainer, val path: String) : ViewModel() {
    val player: ExoPlayer = ExoPlayer.Builder(container.app)
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

    private val _state = MutableStateFlow(VideoState())
    val state: StateFlow<VideoState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
            if (!isPlaying) savePosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(buffering = playbackState == Player.STATE_BUFFERING)
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = describe(error), buffering = false)
        }

        override fun onTracksChanged(tracks: Tracks) {
            val audio = tracks.groups.withIndex().filter { it.value.type == C.TRACK_TYPE_AUDIO }
            val options = audio.mapIndexed { n, (index, group) ->
                val f = group.getTrackFormat(0)
                val label = listOfNotNull(
                    f.label,
                    f.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.forLanguageTag("ru")).ifBlank { it } },
                    f.channelCount.takeIf { it > 0 }?.let { "${it} кан." },
                ).joinToString(" • ").ifBlank { "Дорожка ${n + 1}" }
                AudioTrackOption(index, label, group.isSelected)
            }
            val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            val info = video?.let { g ->
                val f = g.getTrackFormat((0 until g.length).firstOrNull { g.isTrackSelected(it) } ?: 0)
                listOfNotNull(
                    if (f.width > 0) "${f.width}×${f.height}" else null,
                    if (f.frameRate > 0) "%.0f к/с".format(f.frameRate) else null,
                    f.sampleMimeType?.removePrefix("video/")?.uppercase(),
                    f.codecs,
                ).joinToString(" • ")
            }
            _state.value = _state.value.copy(
                hasSubtitles = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT },
                audioTracks = options,
                videoInfo = info,
            )
        }
    }

    init {
        player.addListener(listener)
        viewModelScope.launch {
            val file = File(path)
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setSubtitleConfigurations(externalSubtitles(file))
                .build()
            val resume = container.playback.get(path)
            player.setMediaItem(item, resume)
            player.prepare()
            player.playWhenReady = true
            _state.value = _state.value.copy(resumedFromMs = resume)
        }
    }

    fun togglePlay() {
        if (_state.value.error != null) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    fun setSubtitles(on: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
            .build()
        _state.value = _state.value.copy(subtitlesOn = on)
    }

    fun selectAudio(groupIndex: Int) {
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    fun pause() = player.pause()

    fun savePosition() {
        val pos = player.currentPosition
        val dur = player.duration
        if (dur == C.TIME_UNSET) return
        container.appScope.launch { container.playback.save(path, pos, dur) }
    }

    override fun onCleared() {
        savePosition()
        player.removeListener(listener)
        player.release()
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "Устройство не поддерживает кодек или разрешение этого видео. Видео не перекодируется автоматически."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            "Формат контейнера не поддерживается или файл повреждён."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Файл не найден."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Нет доступа к файлу."
        else -> "Не удалось воспроизвести: ${error.errorCodeName}."
    }

    companion object {
        /** Внешние субтитры с тем же именем рядом с видео: .srt, .vtt, .ass/.ssa. */
        fun externalSubtitles(video: File): List<MediaItem.SubtitleConfiguration> {
            val base = video.nameWithoutExtension
            val siblings = video.parentFile?.listFiles().orEmpty()
            return siblings.filter { it.isFile && it.nameWithoutExtension.startsWith(base) && it != video }
                .mapNotNull { f ->
                    val mime = when (f.extension.lowercase()) {
                        "srt" -> MimeTypes.APPLICATION_SUBRIP
                        "vtt" -> MimeTypes.TEXT_VTT
                        "ass", "ssa" -> MimeTypes.TEXT_SSA
                        else -> null
                    } ?: return@mapNotNull null
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                        .setMimeType(mime)
                        .setLabel(f.name)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                }
        }
    }
}

fun formatPlaybackTime(ms: Long): String {
    if (ms < 0 || ms == C.TIME_UNSET) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Встроенный видеоплеер (ТЗ §4): двойное касание слева −10 с, справа +10 с; касание — показать/скрыть
 * контролы; полноэкранный режим и горизонтальная ориентация — только кнопками, без датчика.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(path: String) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val vm = appViewModel(key = "video-$path") { VideoPlayerViewModel(it, path) }
    val state by vm.state.collectAsStateWithLifecycle()
    val player = vm.player

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var landscape by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var seekHintLeft by remember { mutableStateOf(true) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // Ориентация: только по кнопке, датчик не используется.
    val originalOrientation = remember(activity) { activity?.requestedOrientation }
    LaunchedEffect(activity, landscape) {
        activity?.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    // Полный экран — только по кнопке.
    LaunchedEffect(fullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(window, view)
        if (fullscreen) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
            if (activity != null && originalOrientation != null) activity.requestedOrientation = originalOrientation
        }
    }
    // Экран не гаснет во время воспроизведения.
    DisposableEffect(state.isPlaying) {
        view.keepScreenOn = state.isPlaying
        onDispose { view.keepScreenOn = false }
    }
    // Свернули приложение — пауза (фоновое воспроизведение не нужно).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(250)
        }
    }
    // Контролы прячутся через несколько секунд, пока видео идёт.
    LaunchedEffect(controlsVisible, state.isPlaying, interaction, menuOpen) {
        if (controlsVisible && state.isPlaying && !menuOpen) {
            delay(3_500)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(700)
            seekHint = null
        }
    }
    LaunchedEffect(state.resumedFromMs) {
        if (state.resumedFromMs > 0) {
            FileActions.toast(context, "Продолжено с ${formatPlaybackTime(state.resumedFromMs)}")
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    this.player = player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // Слой жестов поверх видео.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            interaction++
                        },
                        onDoubleTap = { offset ->
                            val left = offset.x < size.width / 2f
                            vm.seekBy(if (left) -SEEK_STEP_MS else SEEK_STEP_MS)
                            seekHintLeft = left
                            seekHint = if (left) "−10 с" else "+10 с"
                            interaction++
                        },
                    )
                },
        )

        seekHint?.let { hint ->
            Text(
                hint,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .align(if (seekHintLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 48.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        state.error?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.padding(8.dp))
                Button(onClick = { FileActions.openExternally(context, FileEntry.from(File(path))) }) {
                    Text("Открыть в другом приложении")
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible || state.error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { navigator.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
                }
                Text(
                    File(path).name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && state.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatPlaybackTime(if (dragging) dragValue.toLong() else position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = if (dragging) dragValue else position.toFloat().coerceAtMost(duration.toFloat()),
                        onValueChange = {
                            dragging = true
                            dragValue = it
                            interaction++
                        },
                        onValueChangeFinished = {
                            vm.seekTo(dragValue.toLong())
                            dragging = false
                        },
                        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(formatPlaybackTime(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.togglePlay(); interaction++ }) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Пауза" else "Воспроизвести",
                            tint = Color.White,
                        )
                    }
                    Row {
                        IconButton(onClick = { landscape = !landscape; interaction++ }) {
                            Icon(
                                Icons.Filled.ScreenRotation,
                                contentDescription = if (landscape) "Вертикально" else "Горизонтально",
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = { fullscreen = !fullscreen; interaction++ }) {
                            Icon(
                                if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = if (fullscreen) "Выйти из полноэкранного режима" else "Полный экран",
                                tint = Color.White,
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Параметры", tint = Color.White)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                Text(
                                    "Скорость",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                                speeds.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text((if (s == state.speed) "✓ " else "") + "${s}×".replace(".0×", "×")) },
                                        onClick = { vm.setSpeed(s); menuOpen = false },
                                    )
                                }
                                if (state.hasSubtitles) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(if (state.subtitlesOn) "Выключить субтитры" else "Включить субтитры") },
                                        onClick = { vm.setSubtitles(!state.subtitlesOn); menuOpen = false },
                                    )
                                }
                                if (state.audioTracks.size > 1) {
                                    HorizontalDivider()
                                    Text(
                                        "Звуковая дорожка",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                    state.audioTracks.forEach { t ->
                                        DropdownMenuItem(
                                            text = { Text((if (t.selected) "✓ " else "") + t.label) },
                                            onClick = { vm.selectAudio(t.groupIndex); menuOpen = false },
                                        )
                                    }
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Сведения о видео") },
                                    onClick = { infoOpen = true; menuOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Открыть в другом приложении") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        vm.pause()
                                        FileActions.openExternally(context, FileEntry.from(File(path)))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (infoOpen) {
        AlertDialog(
            onDismissRequest = { infoOpen = false },
            title = { Text("Сведения о видео") },
            text = {
                Column {
                    Text(File(path).name)
                    Spacer(Modifier.width(8.dp))
                    Text(state.videoInfo ?: "Видеодорожка ещё не определена")
                    Text("Длительность: ${formatPlaybackTime(duration)}")
                    Text("Скорость: ${state.speed}×")
                }
            },
            confirmButton = { TextButton(onClick = { infoOpen = false }) { Text("Закрыть") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
}
