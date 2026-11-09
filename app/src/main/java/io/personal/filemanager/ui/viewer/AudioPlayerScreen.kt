package io.personal.filemanager.ui.viewer

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.personal.filemanager.core.FileTypes
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.AudioViewerSession
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * Built-in audio player for local files. Reuses the Media3 engine already installed for video.
 * Playback stops when leaving this screen and pauses when the app goes to background.
 */
@Composable
fun AudioPlayerScreen(path: String) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val paths = remember(path) {
        AudioViewerSession.paths.filter { FileTypes.kindOf(File(it).name) ==
            io.personal.filemanager.core.FileKind.AUDIO && File(it).isFile }
            .distinct()
            .let { list -> if (path in list) list else listOf(path) }
    }
    var selected by remember(path) { mutableIntStateOf(paths.indexOf(path).coerceAtLeast(0)) }
    val player = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setHandleAudioBecomingNoisy(true)
        }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var length by remember { mutableLongStateOf(0L) }
    var progress by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragTo by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(1f) }
    var error by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf(false) }
    val currentPath = paths.getOrElse(selected) { path }
    val currentFile = remember(currentPath) { File(currentPath) }
    val fileName = currentFile.name.ifBlank { "Аудиофайл" }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlaybackStateChanged(state: Int) {
                prepared = state == Player.STATE_READY
                length = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                if (state == Player.STATE_ENDED) {
                    // Do not auto-play across folders or unexpectedly loop private voice notes.
                    player.seekTo(0)
                    player.pause()
                    progress = 0
                }
            }
            override fun onPlayerError(failure: PlaybackException) {
                error = failure.localizedMessage ?: "Формат файла не удалось воспроизвести"
                prepared = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(currentPath) {
        error = null
        prepared = false
        isPlaying = false
        progress = 0
        length = 0
        if (!currentFile.isFile || !currentFile.canRead()) {
            error = "Файл удалён или недоступен для чтения"
            player.stop()
        } else {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(currentFile)))
            player.prepare()
            player.setPlaybackSpeed(speed)
            player.playWhenReady = true
        }
    }

    LaunchedEffect(player) {
        while (true) {
            if (!dragging) progress = player.currentPosition.coerceAtLeast(0)
            length = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            delay(300L)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player.pause()
    }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navigator.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text(
                "Аудиоплеер",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = {
                val entry = io.personal.filemanager.data.FileEntry.from(currentFile)
                FileActions.share(context, listOf(entry))
            }, enabled = currentFile.isFile) {
                Icon(Icons.Default.Share, contentDescription = "Поделиться аудиофайлом")
            }
        }
        Spacer(Modifier.weight(0.6f))
        Surface(
            modifier = Modifier.align(Alignment.CenterHorizontally).size(160.dp),
            shape = RoundedCornerShape(34.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(84.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            fileName,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (paths.size > 1) "Файл ${selected + 1} из ${paths.size}" else "Локальный аудиофайл",
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (error != null) {
            Text(
                error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.weight(0.4f))
        val safeDuration = length.coerceAtLeast(1L)
        Slider(
            value = if (dragging) dragTo else (progress.toFloat() / safeDuration).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragTo = it },
            onValueChangeFinished = {
                if (length > 0L) player.seekTo((dragTo * length).toLong().coerceIn(0L, length))
                dragging = false
            },
            enabled = prepared && length > 0L,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(audioTime(if (dragging) (dragTo * length).toLong() else progress),
                style = MaterialTheme.typography.labelMedium)
            Text(audioTime(length), style = MaterialTheme.typography.labelMedium)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { selected = (selected - 1).coerceAtLeast(0) },
                enabled = selected > 0,
                modifier = Modifier.size(54.dp),
            ) {
                Icon(Icons.Default.SkipPrevious, "Предыдущий аудиофайл", modifier = Modifier.size(34.dp))
            }
            FilledIconButton(
                onClick = {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    if (player.isPlaying) player.pause() else player.play()
                },
                enabled = prepared && error == null,
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (isPlaying) "Пауза" else "Воспроизвести",
                    modifier = Modifier.size(38.dp),
                )
            }
            IconButton(
                onClick = { selected = (selected + 1).coerceAtMost(paths.lastIndex) },
                enabled = selected < paths.lastIndex,
                modifier = Modifier.size(54.dp),
            ) {
                Icon(Icons.Default.SkipNext, "Следующий аудиофайл", modifier = Modifier.size(34.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0)) },
                enabled = prepared) { Text("−10 с") }
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text("${speed}×") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { option ->
                        DropdownMenuItem(
                            text = { Text("${option}×") },
                            onClick = {
                                speed = option
                                player.setPlaybackSpeed(option)
                                expanded = false
                            },
                        )
                    }
                }
            }
            TextButton(onClick = {
                val max = length.takeIf { it > 0L } ?: Long.MAX_VALUE
                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(max))
            }, enabled = prepared) { Text("+10 с") }
        }
        Spacer(Modifier.weight(0.5f))
    }
}

private fun audioTime(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1_000L)
    return if (seconds >= 3_600L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600L, seconds / 60L % 60L, seconds % 60L)
    } else {
        String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)
    }
}
