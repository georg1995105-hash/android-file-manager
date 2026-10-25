package io.personal.filemanager.ui.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.settings.UiConfig
import io.personal.filemanager.data.settings.ViewerControl
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.components.FileDetailsSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Панели просмотра скрываются через это время без касаний. */
const val VIEWER_AUTO_HIDE_MS = 3_000L

/**
 * Просмотр фото и видео (ТЗ §4): перелистывание соседних файлов свайпом влево/вправо; у фото — масштаб
 * щипком и двойным касанием; у видео — двойное касание слева −10 с, справа +10 с. Одно касание показывает
 * или прячет панели, без касаний они скрываются сами. Поворот — только кнопкой, датчик не используется:
 * просмотр открывается в текущей ориентации устройства (на планшете в горизонтальной — горизонтально).
 */
@OptIn(UnstableApi::class)
@Composable
fun MediaViewerScreen(path: String) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findActivity() }
    val container = appContainer()
    val vm = appViewModel(key = "media-$path") { MediaViewerViewModel(it, path) }
    val items by vm.items.collectAsStateWithLifecycle()
    val video by vm.video.collectAsStateWithLifecycle()
    val config by container.settings.uiConfig.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    if (items.isEmpty()) {
        LaunchedEffect(Unit) { navigator.back() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val pagerState = rememberPagerState(initialPage = items.indexOf(path).coerceAtLeast(0)) { items.size }
    val currentIndex = pagerState.currentPage.coerceIn(0, items.lastIndex)
    val currentPath = items[currentIndex]
    val settledPath = items[pagerState.settledPage.coerceIn(0, items.lastIndex)]
    val currentIsVideo = MediaViewerViewModel.isVideo(currentPath)
    val videoActive = currentIsVideo && video.path == currentPath

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var customizeOpen by remember { mutableStateOf(false) }
    var videoInfoOpen by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<FileEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var seekHintLeft by remember { mutableStateOf(true) }
    val holdControls = menuOpen || customizeOpen || videoInfoOpen || detailsFor != null || confirmDelete != null || dragging

    fun touch() {
        interaction++
    }

    // Текущая страница: видео загружается и играет только после остановки перелистывания.
    LaunchedEffect(settledPath) { vm.onCurrent(settledPath) }

    // Ориентация: просмотр фиксирует ту, в которой открыт (без датчика); кнопка поворота меняет её явно.
    // При выходе восстанавливается исходная настройка активности.
    DisposableEffect(activity) {
        if (activity != null) {
            if (vm.originalOrientation == null) vm.originalOrientation = activity.requestedOrientation
            activity.requestedOrientation = vm.lockedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
        onDispose {
            if (activity != null && !activity.isChangingConfigurations) {
                vm.originalOrientation?.let { activity.requestedOrientation = it }
            }
        }
    }
    // Android 16 на больших экранах игнорирует запросы ориентации — кнопка там бесполезна.
    val canRotate = !(Build.VERSION.SDK_INT >= 36 && configuration.smallestScreenWidthDp >= 600)
    fun rotate() {
        val landscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val target = if (landscapeNow) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        vm.lockedOrientation = target
        activity?.requestedOrientation = target
    }

    // Системные панели скрыты, пока скрыты панели просмотра или включён полный экран.
    LaunchedEffect(controlsVisible, fullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(window, view)
        if (!controlsVisible || fullscreen) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    // Автоскрытие панелей (ТЗ: 2–3 с), пока пользователь не работает с меню, диалогом или ползунком.
    LaunchedEffect(controlsVisible, interaction, holdControls) {
        if (controlsVisible && !holdControls) {
            delay(VIEWER_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }
    // Экран не гаснет во время воспроизведения; при сворачивании — пауза.
    DisposableEffect(video.isPlaying) {
        view.keepScreenOn = video.isPlaying
        onDispose { view.keepScreenOn = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }
    // Позиция для ползунка опрашивается, только когда он виден.
    LaunchedEffect(videoActive, controlsVisible) {
        if (!videoActive || !controlsVisible) return@LaunchedEffect
        val player = vm.player
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(250)
        }
    }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(700)
            seekHint = null
        }
    }
    LaunchedEffect(video.path, video.resumedFromMs) {
        if (video.resumedFromMs > 0) FileActions.toast(context, "Продолжено с ${formatPlaybackTime(video.resumedFromMs)}")
    }

    val controls = (config?.viewerControls ?: UiConfig.defaultViewerControls())
        .filter { it.visible }
        .mapNotNull { ViewerControl.fromKey(it.id) }
    fun entryOf(p: String) = FileEntry.from(File(p))
    fun goTo(index: Int) {
        if (index in items.indices) scope.launch { pagerState.animateScrollToPage(index) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { items[it] },
            beyondViewportPageCount = 1,
        ) { page ->
            val pagePath = items[page]
            if (MediaViewerViewModel.isVideo(pagePath)) {
                VideoPage(
                    path = pagePath,
                    active = video.path == pagePath && page == pagerState.settledPage,
                    vm = vm,
                    video = video,
                    onTap = { controlsVisible = !controlsVisible; touch() },
                    onDoubleTap = { left ->
                        vm.seekBy(if (left) -SEEK_STEP_MS else SEEK_STEP_MS)
                        seekHintLeft = left
                        seekHint = if (left) "−10 с" else "+10 с"
                        touch()
                    },
                )
            } else {
                ZoomableImage(
                    path = pagePath,
                    isCurrent = page == pagerState.currentPage,
                    onTap = { controlsVisible = !controlsVisible; touch() },
                )
            }
        }

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

        AnimatedVisibility(
            visible = controlsVisible || (videoActive && video.error != null),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { navigator.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        File(currentPath).name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (items.size > 1) {
                        Text(
                            "${currentIndex + 1} из ${items.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                if (videoActive && video.error == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatPlaybackTime(if (dragging) dragValue.toLong() else position),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = if (dragging) dragValue else position.toFloat().coerceAtMost(duration.toFloat()),
                            onValueChange = {
                                dragging = true
                                dragValue = it
                            },
                            onValueChangeFinished = {
                                vm.seekTo(dragValue.toLong())
                                dragging = false
                                touch()
                            },
                            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        Text(formatPlaybackTime(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .widthIn(min = maxWidth),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        controls.forEach { control ->
                            when (control) {
                                ViewerControl.PLAY_PAUSE -> if (videoActive && video.error == null) {
                                    ControlButton(
                                        if (video.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        if (video.isPlaying) "Пауза" else "Воспроизвести",
                                    ) { vm.togglePlay(); touch() }
                                }
                                ViewerControl.PREV -> ControlButton(Icons.Filled.ChevronLeft, "Предыдущий файл", enabled = currentIndex > 0) {
                                    goTo(currentIndex - 1); touch()
                                }
                                ViewerControl.NEXT -> ControlButton(Icons.Filled.ChevronRight, "Следующий файл", enabled = currentIndex < items.lastIndex) {
                                    goTo(currentIndex + 1); touch()
                                }
                                ViewerControl.SHARE -> ControlButton(Icons.Filled.Share, "Поделиться") {
                                    FileActions.share(context, listOf(entryOf(currentPath))); touch()
                                }
                                ViewerControl.ROTATE -> if (canRotate) {
                                    val landscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                                    ControlButton(Icons.Filled.ScreenRotation, if (landscapeNow) "Вертикально" else "Горизонтально") {
                                        rotate(); touch()
                                    }
                                }
                                ViewerControl.FULLSCREEN -> ControlButton(
                                    if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                    if (fullscreen) "Выйти из полноэкранного режима" else "Полный экран",
                                ) { fullscreen = !fullscreen; touch() }
                                ViewerControl.INFO -> ControlButton(Icons.Filled.Info, "Сведения") { detailsFor = entryOf(currentPath) }
                                ViewerControl.DELETE -> ControlButton(Icons.Filled.Delete, "В корзину") { confirmDelete = currentPath }
                                ViewerControl.OPEN_EXTERNAL -> ControlButton(Icons.AutoMirrored.Filled.OpenInNew, "Открыть в другом приложении") {
                                    vm.pause(); FileActions.openExternally(context, entryOf(currentPath))
                                }
                            }
                        }
                        Box {
                            ControlButton(Icons.Filled.MoreVert, "Ещё") { menuOpen = true }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false; touch() }) {
                                if (videoActive) {
                                    Text(
                                        "Скорость",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                    playbackSpeeds.forEach { s ->
                                        DropdownMenuItem(
                                            text = { Text((if (s == video.speed) "✓ " else "") + "${s}×".replace(".0×", "×")) },
                                            onClick = { vm.setSpeed(s); menuOpen = false },
                                        )
                                    }
                                    if (video.hasSubtitles) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(if (video.subtitlesOn) "Выключить субтитры" else "Включить субтитры") },
                                            onClick = { vm.setSubtitles(!video.subtitlesOn); menuOpen = false },
                                        )
                                    }
                                    if (video.audioTracks.size > 1) {
                                        HorizontalDivider()
                                        Text(
                                            "Звуковая дорожка",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        )
                                        video.audioTracks.forEach { t ->
                                            DropdownMenuItem(
                                                text = { Text((if (t.selected) "✓ " else "") + t.label) },
                                                onClick = { vm.selectAudio(t.groupIndex); menuOpen = false },
                                            )
                                        }
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Сведения о видео") },
                                        onClick = { videoInfoOpen = true; menuOpen = false },
                                    )
                                }
                                if (ViewerControl.INFO !in controls) {
                                    DropdownMenuItem(
                                        text = { Text("Сведения о файле") },
                                        onClick = { detailsFor = entryOf(currentPath); menuOpen = false },
                                    )
                                }
                                if (ViewerControl.OPEN_EXTERNAL !in controls) {
                                    DropdownMenuItem(
                                        text = { Text("Открыть в другом приложении") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            vm.pause()
                                            FileActions.openExternally(context, entryOf(currentPath))
                                        },
                                    )
                                }
                                if (ViewerControl.DELETE !in controls) {
                                    DropdownMenuItem(
                                        text = { Text("В корзину") },
                                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                        onClick = { menuOpen = false; confirmDelete = currentPath },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Настроить кнопки…") },
                                    onClick = { menuOpen = false; customizeOpen = true },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { target ->
        val name = File(target).name
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Переместить в корзину?") },
            text = { Text("«$name» будет перемещён в корзину приложения. Восстановить его можно в «Меню → Корзина».") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    if (video.path == target) vm.stopVideo()
                    val started = container.operations.moveToTrash(listOf(target)) { report ->
                        if (report != null && File(target).absolutePath in report.done) {
                            vm.remove(target)
                        } else if (target == settledPath) {
                            vm.onCurrent(target)
                        }
                    }
                    if (!started && target == settledPath) vm.onCurrent(target)
                }) { Text("В корзину") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }

    if (videoInfoOpen) {
        AlertDialog(
            onDismissRequest = { videoInfoOpen = false },
            title = { Text("Сведения о видео") },
            text = {
                Column {
                    Text(File(currentPath).name)
                    Spacer(Modifier.height(8.dp))
                    Text(video.videoInfo ?: "Видеодорожка ещё не определена")
                    Text("Длительность: ${formatPlaybackTime(duration)}")
                    Text("Скорость: ${video.speed}×")
                }
            },
            confirmButton = { TextButton(onClick = { videoInfoOpen = false }) { Text("Закрыть") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }

    if (customizeOpen) {
        ViewerControlsDialog(
            current = config?.viewerControls ?: UiConfig.defaultViewerControls(),
            onSave = { list -> scope.launch { container.settings.updateUiConfig { it.copy(viewerControls = list) } } },
            onDismiss = { customizeOpen = false },
        )
    }

    detailsFor?.let { FileDetailsSheet(it, onDismiss = { detailsFor = null }) }
}

@Composable
private fun ControlButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f))
    }
}

/** Страница видео: активная — плеер, остальные — кадр-обложка. Касания: одно — панели, двойное — ±10 с. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    path: String,
    active: Boolean,
    vm: MediaViewerViewModel,
    video: VideoState,
    onTap: () -> Unit,
    onDoubleTap: (left: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val isActive by rememberUpdatedState(active)
    Box(Modifier.fillMaxSize()) {
        if (active) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(AndroidColor.BLACK)
                        player = vm.player
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!active || !video.firstFrameShown) {
            // Обложка до первого кадра: перелистывание не мигает чёрным.
            AsyncImage(
                model = File(path),
                contentDescription = File(path).name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            if (!active) {
                Icon(
                    Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp),
                )
            }
        }
        // Слой жестов поверх видео; горизонтальные свайпы не перехватываются — ими листаются файлы.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { tap() },
                        onDoubleTap = { offset -> if (isActive) doubleTap(offset.x < size.width / 2f) },
                    )
                },
        )
        if (active) {
            video.error?.let { message ->
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { FileActions.openExternally(context, FileEntry.from(File(path))) }) {
                        Text("Открыть в другом приложении")
                    }
                }
            }
        }
    }
}
