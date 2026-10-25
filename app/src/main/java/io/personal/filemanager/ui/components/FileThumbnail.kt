package io.personal.filemanager.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.SubcomposeAsyncImage
import io.personal.filemanager.FileManagerApp
import io.personal.filemanager.core.FileKind
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.ui.theme.AccentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Suppress("DEPRECATION")
fun kindIcon(kind: FileKind): Pair<ImageVector, Color> = when (kind) {
    FileKind.FOLDER -> Icons.Filled.Folder to AccentColors.of("blue")
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

/** Плашка с цветным значком типа файла (как в макете: PDF — красная, APK — зелёная). */
@Composable
fun KindBadge(kind: FileKind, size: Dp, modifier: Modifier = Modifier) {
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
                SubcomposeAsyncImage(
                    model = File(entry.path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { KindBadge(entry.kind, size) },
                    error = { KindBadge(entry.kind, size) },
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
    val bitmap by produceState<ImageBitmap?>(null, entry.path) {
        value = if (entry.extension == "apk") {
            withContext(Dispatchers.IO) { apps.apkArchive(entry.path)?.second?.toImage() }
        } else {
            null
        }
    }
    val image = bitmap
    if (image != null) {
        Image(image, contentDescription = null, modifier = modifier.size(size).padding(size / 12))
    } else {
        KindBadge(FileKind.APK, size, modifier)
    }
}

fun Drawable.toImage(px: Int = 144): ImageBitmap? =
    runCatching { toBitmap(px, px).asImageBitmap() }.getOrNull()
