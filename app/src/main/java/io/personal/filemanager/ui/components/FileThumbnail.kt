package io.personal.filemanager.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import io.personal.filemanager.FileManagerApp
import io.personal.filemanager.core.FileKind
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.ui.theme.AccentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Suppress("DEPRECATION")
fun kindIcon(kind: FileKind): Pair<ImageVector, Color> = when (kind) {
    FileKind.FOLDER -> Icons.Filled.Folder to Color(0xFFE6B16B)
    FileKind.IMAGE -> Icons.Filled.Image to AccentColors.of("purple")
    FileKind.VIDEO -> Icons.Filled.Movie to AccentColors.of("red")
    FileKind.AUDIO -> Icons.Filled.MusicNote to AccentColors.of("pink")
    FileKind.PDF -> Icons.Filled.PictureAsPdf to AccentColors.of("red")
    FileKind.TEXT -> Icons.Filled.Article to AccentColors.of("blue_light")
    FileKind.DOCUMENT -> Icons.Filled.Description to AccentColors.of("blue")
    FileKind.ARCHIVE -> Icons.Filled.FolderZip to AccentColors.of("orange")
    FileKind.APK -> Icons.Filled.Android to AccentColors.of("green")
    FileKind.OTHER -> Icons.Filled.InsertDriveFile to AccentColors.of("gray")
}

/**
 * Тёплая папка с заметным силуэтом и бумажными вкладками, без синей квадратной подложки.
 * Рисуется локально Canvas: не зависит от ресурсов, темы или загрузки изображений.
 */
@Composable
private fun FolderThumbnail(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 64f
        val x = (this.size.width - 64f * unit) / 2f
        val y = (this.size.height - 64f * unit) / 2f
        fun point(px: Float, py: Float) = Offset(x + px * unit, y + py * unit)
        fun rect(
            px: Float, py: Float, w: Float, h: Float,
            radius: Float, color: Color,
        ) {
            drawRoundRect(
                color = color,
                topLeft = point(px, py),
                size = Size(w * unit, h * unit),
                cornerRadius = CornerRadius(radius * unit),
            )
        }
        // Задняя часть, корешок и видимые края документов.
        rect(5f, 17f, 54f, 39f, 6f, Color(0xFFB67D3B))
        rect(7f, 10f, 26f, 20f, 5f, Color(0xFFE6B16B))
        rect(15f, 15f, 38f, 28f, 3f, Color(0xFFF7E2BD))
        rect(12f, 19f, 41f, 26f, 3f, Color(0xFFFFF1D9))
        // Передняя поверхность: мягкий янтарный оттенок, как у обычной папки.
        rect(4f, 25f, 56f, 32f, 6f, Color(0xFFE0A459))
        rect(7f, 27f, 50f, 2f, 1f, Color(0xFFF1C384))
    }
}

/** Понятные иконки файлов, без лишней подложки у папок. */
@Composable
fun KindBadge(kind: FileKind, size: Dp, modifier: Modifier = Modifier) {
    if (kind == FileKind.FOLDER) {
        FolderThumbnail(size, modifier)
        return
    }
    val (icon, color) = kindIcon(kind)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.58f))
    }
}

/** Миниатюра: фото/видео — кадр, APK — иконка пакета, остальное — значок типа. */
@Composable
fun FileThumbnail(entry: FileEntry, size: Dp, modifier: Modifier = Modifier) {
    when (entry.kind) {
        FileKind.IMAGE, FileKind.VIDEO -> {
            Box(modifier.size(size).clip(RoundedCornerShape(size / 4))) {
                // Значок типа под миниатюрой: виден, пока она грузится или если её нет. AsyncImage без
                // подкомпозиции — дешевле в длинных списках.
                KindBadge(entry.kind, size)
                AsyncImage(
                    model = MediaThumb.of(entry),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (entry.kind == FileKind.VIDEO) {
                    Icon(
                        Icons.Filled.PlayCircle,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(size * 0.42f),
                    )
                }
            }
        }
        FileKind.APK -> ApkThumbnail(entry, size, modifier)
        else -> KindBadge(entry.kind, size, modifier)
    }
}

@Composable
private fun ApkThumbnail(entry: FileEntry, size: Dp, modifier: Modifier) {
    val context = LocalContext.current
    val apps = remember { (context.applicationContext as FileManagerApp).container.apps }
    val visual by produceState<Pair<Boolean, ImageBitmap?>?>(null, entry.path) {
        value = if (entry.extension == "apk") {
            withContext(Dispatchers.IO) {
                apps.apkArchive(entry.path)?.let { data ->
                    (data.first.packageName == context.packageName) to data.second?.toImage()
                }
            }
        } else null
    }
    val image = visual?.second
    if (visual?.first == true) {
        // APK is an application package, never a directory — show its type explicitly.
        KindBadge(FileKind.APK, size, modifier)
    } else if (image != null) {
        Image(image, contentDescription = null, modifier = modifier.size(size).padding(size / 12))
    } else {
        KindBadge(FileKind.APK, size, modifier)
    }
}

fun Drawable.toImage(px: Int = 144): ImageBitmap? =
    runCatching { toBitmap(px, px).asImageBitmap() }.getOrNull()
