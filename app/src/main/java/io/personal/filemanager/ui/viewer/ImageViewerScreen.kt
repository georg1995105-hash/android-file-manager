package io.personal.filemanager.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.ImageViewerSession
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.components.FileDetailsSheet
import java.io.File

/** Максимальная сторона декодируемого кадра: защищает память на снимках 50–200 Мп. */
private const val MAX_DECODE_PX = 4096

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Встроенный просмотр фото: перелистывание, жесты масштаба, двойное касание, касание скрывает интерфейс.
 * Ориентация экрана не зависит от датчика; горизонтальный режим — только кнопкой.
 */
@Composable
fun ImageViewerScreen(path: String) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val paths = remember(path) { ImageViewerSession.paths.takeIf { path in it } ?: listOf(path) }
    val pagerState = rememberPagerState(initialPage = paths.indexOf(path).coerceAtLeast(0)) { paths.size }
    var uiVisible by rememberSaveable { mutableStateOf(true) }
    var landscape by rememberSaveable { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<FileEntry?>(null) }
    val view = LocalView.current

    // Ориентация — только по явной кнопке, без датчика.
    val originalOrientation = remember(activity) { activity?.requestedOrientation }
    LaunchedEffect(activity, landscape) {
        activity?.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    DisposableEffect(activity) {
        onDispose {
            if (activity != null && originalOrientation != null) activity.requestedOrientation = originalOrientation
        }
    }
    // Полноэкранный режим, когда интерфейс скрыт.
    LaunchedEffect(uiVisible) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (uiVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    val currentPath = paths.getOrElse(pagerState.currentPage) { path }
    val currentName = File(currentPath).name

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { paths[it] },
            beyondViewportPageCount = 1,
        ) { page ->
            ZoomableImage(
                path = paths[page],
                isCurrent = page == pagerState.currentPage,
                onTap = { uiVisible = !uiVisible },
            )
        }

        if (uiVisible) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { navigator.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(currentName, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (paths.size > 1) {
                        Text("${pagerState.currentPage + 1} из ${paths.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val entry = { FileEntry.from(File(currentPath)) }
                IconButton(onClick = { FileActions.share(context, listOf(entry())) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Поделиться", tint = Color.White)
                }
                IconButton(onClick = { landscape = !landscape }) {
                    Icon(
                        Icons.Filled.ScreenRotation,
                        contentDescription = if (landscape) "Вертикально" else "Горизонтально",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { FileActions.openExternally(context, entry()) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Открыть в другом приложении", tint = Color.White)
                }
                IconButton(onClick = { detailsFor = entry() }) {
                    Icon(Icons.Filled.Info, contentDescription = "Сведения", tint = Color.White)
                }
            }
        }
    }
    detailsFor?.let { FileDetailsSheet(it, onDismiss = { detailsFor = null }) }
}

@Composable
private fun ZoomableImage(path: String, isCurrent: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    val request = remember(path) {
        ImageRequest.Builder(context)
            .data(File(path))
            .size(MAX_DECODE_PX)
            .build()
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val target = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = clamp((center - tap) * (target - 1f), target)
                            scale = target
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        // Один палец без увеличения — жест отдаётся перелистыванию.
                        if (pressed >= 2 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (newScale <= 1f) Offset.Zero else clamp(offset + event.calculatePan(), newScale)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = request,
            contentDescription = File(path).name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Не удалось открыть изображение", color = Color.White)
                }
            },
        )
    }
}
