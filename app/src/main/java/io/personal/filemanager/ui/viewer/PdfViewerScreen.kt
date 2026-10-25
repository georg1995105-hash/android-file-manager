package io.personal.filemanager.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.components.EmptyState
import io.personal.filemanager.ui.components.ScreenTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Открытый PDF: рендерер не потокобезопасен, поэтому все обращения — под мьютексом. */
private class PdfDocument(file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    val mutex = Mutex()
    val pageCount: Int get() = renderer.pageCount

    fun aspect(index: Int): Float = renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) }

    /** Страница шириной [widthPx] (с ограничением памяти). */
    fun render(index: Int, widthPx: Int): Bitmap = renderer.openPage(index).use { page ->
        val w = widthPx.coerceIn(200, 2400)
        val h = (w.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(200, 4000)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(AndroidColor.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp
    }

    override fun close() {
        renderer.close()
        fd.close()
    }
}

/**
 * Встроенный просмотр PDF через системный PdfRenderer: страницы подряд, двойное касание — увеличить
 * (горизонтальная прокрутка). Защищённые паролем и повреждённые PDF — честная ошибка и внешнее приложение.
 */
@Composable
fun PdfViewerScreen(path: String) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val file = remember(path) { File(path) }
    val doc by produceState<Result<PdfDocument>?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { PdfDocument(file) } }
    }
    DisposableEffect(doc) {
        val opened = doc?.getOrNull()
        onDispose { opened?.close() }
    }
    var zoomed by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        val d = doc?.getOrNull()
        ScreenTopBar(
            title = file.name,
            subtitle = d?.let { "Страница ${listState.firstVisibleItemIndex + 1} из ${it.pageCount}" + if (zoomed) " • увеличено" else "" },
            onBack = { navigator.back() },
            actions = {
                TextButton(onClick = { FileActions.openExternally(context, FileEntry.from(file)) }) { Text("Открыть в…") }
            },
        )
        when {
            doc == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            d == null -> EmptyState(
                Icons.Filled.ErrorOutline,
                "Не удалось открыть PDF",
                "Файл защищён паролем, повреждён или использует возможности, которые не поддерживает встроенный просмотр.",
                action = { Button(onClick = { FileActions.openExternally(context, FileEntry.from(file)) }) { Text("Открыть в другом приложении") } },
            )
            else -> BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2A2D33))
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoomed = !zoomed }) },
            ) {
                val density = LocalDensity.current
                val pageWidth = if (zoomed) maxWidth * 2 else maxWidth
                val pagePx = with(density) { pageWidth.roundToPx() }
                val hScroll = rememberScrollState()
                Box(Modifier.fillMaxSize().horizontalScroll(hScroll, enabled = zoomed)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.width(pageWidth).fillMaxHeight(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(d.pageCount) { index -> PdfPage(d, index, pagePx) }
                        item { Box(Modifier.navigationBarsPadding()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDocument, index: Int, widthPx: Int) {
    val aspect by produceState(0.707f, doc, index) {
        value = withContext(Dispatchers.IO) { doc.mutex.withLock { runCatching { doc.aspect(index) }.getOrDefault(0.707f) } }
    }
    val bitmap by produceState<ImageBitmap?>(null, doc, index, widthPx) {
        value = withContext(Dispatchers.IO) {
            doc.mutex.withLock { runCatching { doc.render(index, widthPx).asImageBitmap() }.getOrNull() }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(Color.White, RoundedCornerShape(2.dp))
            .aspectRatio(aspect),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, contentDescription = "Страница ${index + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        }
    }
}
