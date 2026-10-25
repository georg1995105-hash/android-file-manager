package io.personal.filemanager.ui.viewer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import java.io.File

/** Шаг перемотки двойным касанием — ровно 10 секунд (ТЗ §4). */
const val SEEK_STEP_MS = 10_000L

data class AudioTrackOption(val groupIndex: Int, val label: String, val selected: Boolean)

/** Состояние текущего видео в просмотре. */
data class VideoState(
    val path: String? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = true,
    val firstFrameShown: Boolean = false,
    val error: String? = null,
    val hasSubtitles: Boolean = false,
    val subtitlesOn: Boolean = true,
    val audioTracks: List<AudioTrackOption> = emptyList(),
    val speed: Float = 1f,
    val videoInfo: String? = null,
    val resumedFromMs: Long = 0L,
)

fun formatPlaybackTime(ms: Long): String {
    if (ms < 0 || ms == C.TIME_UNSET) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

internal val playbackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

internal object VideoSupport {
    fun describe(error: PlaybackException): String = when (error.errorCode) {
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

    /** Внешние субтитры с тем же именем рядом с видео: .srt, .vtt, .ass/.ssa. Читает папку — вызывать не в UI-потоке. */
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
