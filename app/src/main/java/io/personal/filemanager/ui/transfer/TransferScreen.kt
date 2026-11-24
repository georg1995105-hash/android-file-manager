package io.personal.filemanager.ui.transfer

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.personal.filemanager.data.transfer.LocalShareFiles
import kotlinx.coroutines.*
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.data.transfer.TransferService
import io.personal.filemanager.ui.*

@Composable
fun TransferScreen(incomingShared:List<Uri> = emptyList(), onSharedConsumed:()->Unit = {}) {
    val manager = appContainer().transfer
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val isTv = isTelevisionUi()
    var details by rememberSaveable { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var shareChoice by remember(incomingShared) { mutableStateOf(incomingShared.isNotEmpty()) }
    fun choose(uris:List<Uri>) {
        if(uris.isEmpty() || preparing)return
        preparing=true
        scope.launch {
            try {
                val files=withContext(Dispatchers.IO){LocalShareFiles.stage(context,uris.take(100))}
                manager.share(files)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){manager.message(e.message?:"Не удалось прочитать файлы")}
            finally { preparing=false }
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){choose(it)}
    val start = {
        runCatching { context.startForegroundService(Intent(context, TransferService::class.java)) }
            .onFailure { manager.message(it.message ?: "Ошибка запуска") }
        Unit
    }
    // Открыли «Передачу» — устройство сразу видно другим, без отдельной кнопки (как «Обмен с окружением»).
    LaunchedEffect(Unit) { if (!manager.state.value.running) start() }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Передача", style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = { nav.open(Routes.CLOUD) }, modifier = Modifier.fillMaxWidth()) { Text("Через личный Google Диск") }
        Text("Напрямую по Wi-Fi", style = MaterialTheme.typography.titleMedium)
        if(!isTv) Button(enabled=!preparing,onClick={runCatching{picker.launch(arrayOf("*/*"))}.onFailure{manager.message("Нет приложения для выбора файлов")}},modifier=Modifier.fillMaxWidth()){Text(if(preparing) "Подготовка…" else "Выбрать файлы") }
        if (isTv) {
            if (state.running) {
                TransferQr(state.browserAddresses.firstOrNull(), true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { manager.receivedDirectory.mkdirs(); nav.open(Routes.browser(manager.receivedDirectory.absolutePath)) }) { Text("Полученные файлы") }
                    OutlinedButton(onClick = { context.stopService(Intent(context, TransferService::class.java)) }) { Text("Остановить передачу") }
                }
            } else {
                Text("Подключите телефон и телевизор к одной локальной сети.")
                Button(onClick = start) { Text("Включить передачу") }
            }
            if (state.progress.isNotBlank()) Text(state.progress)
            if (state.message.isNotBlank()) Text(state.message)
            HorizontalDivider()
        }
        Text(
            if (state.running) "Это устройство видно как «${Build.MODEL}». Второе устройство — в той же Wi-Fi сети, на нём тоже открыта «Передача»."
            else "Передача выключена — другие устройства вас не видят.",
        )
        if (state.shared > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("К отправке выбрано: ${state.shared}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = manager::clearShared) { Text("Очистить") }
            }
        } else {
            Text(
                "Чтобы отправить файлы: выберите их в любом списке → «Ещё» → «Передать».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TransferPeerPanel(manager)
        manager.activeReceives().forEach { active ->
            TextButton(onClick = { manager.cancel(active.id) }) { Text("Отменить приём: ${active.description}") }
        }
        if (!isTv) {
            if (state.progress.isNotBlank()) Text(state.progress)
            if (state.message.isNotBlank()) Text(state.message)
            Button(onClick = { manager.receivedDirectory.mkdirs(); nav.open(Routes.browser(manager.receivedDirectory.absolutePath)) }) {
                Text("Полученные файлы")
            }
            HorizontalDivider()
        }
        if (!isTv) {
            if (state.running) {
                TransferQr(state.browserAddresses.firstOrNull(), false)
                OutlinedButton(onClick = { context.stopService(Intent(context, TransferService::class.java)) }) { Text("Остановить передачу") }
            } else {
                Button(onClick = start) { Text("Включить передачу") }
            }
        }
        TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть сведения о безопасности" else "Сведения о безопасности") }
        if (details) {
            Text(
                "Интернет не используется, всё идёт внутри вашей Wi-Fi сети. Подключения и файлы принимаются без вопросов, " +
                    "пока открыта «Передача» — в чужих сетях выключайте её. Между приложениями трафик шифруется; страница по QR — " +
                    "обычный http, чтобы браузер не показывал предупреждений. Отпечаток сертификата (SHA-256):",
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionContainer { Text(state.fingerprint, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = nav::back) { Text("Назад") }
    }
    if(shareChoice && incomingShared.isNotEmpty()) AlertDialog(
        onDismissRequest={shareChoice=false;onSharedConsumed()},
        title={Text("Как отправить файлы?")},text={Text("Файлов: ${incomingShared.size}")},
        confirmButton={TextButton(onClick={shareChoice=false;choose(incomingShared);onSharedConsumed()}){Text("По Wi-Fi")}},
        dismissButton={Row {
            if(io.personal.filemanager.BuildConfig.PFM_DRIVE_ENABLED) TextButton(onClick={shareChoice=false;nav.open(Routes.CLOUD)}){Text("Google Диск")}
            TextButton(onClick={shareChoice=false;onSharedConsumed()}){Text("Отмена")}
        }}
    )
}

@Composable
private fun TransferQr(address: String?, isTv: Boolean) {
    Text(if (isTv) "Отправить с телефона" else "QR для телефона или компьютера", style = MaterialTheme.typography.titleLarge)
    if (address == null) {
        Text("Нет локального адреса. Подключитесь к Wi-Fi или Ethernet и перезапустите передачу.")
        return
    }
    val bitmap = remember(address) {
        runCatching {
            val matrix = com.google.zxing.MultiFormatWriter().encode(address, com.google.zxing.BarcodeFormat.QR_CODE, 256, 256)
            val pixels = IntArray(256 * 256) { i -> if (matrix[i % 256, i / 256]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            android.graphics.Bitmap.createBitmap(pixels, 256, 256, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }
    val instructions: @Composable () -> Unit = {
        Text("Наведите камеру телефона на QR-код, откройте страницу и выберите файлы. Телефон и телевизор должны быть в одной сети.")
        if (isTv) Text(address, style = MaterialTheme.typography.titleMedium)
        else SelectionContainer { Text(address, style = MaterialTheme.typography.titleMedium) }
        if (bitmap == null) Text("QR-код недоступен. Откройте указанный адрес в браузере телефона.")
    }
    val qr: @Composable () -> Unit = {
        bitmap?.let {
            androidx.compose.foundation.Image(
                painter = BitmapPainter(it, filterQuality = FilterQuality.None),
                contentDescription = "QR подключения",
                modifier = Modifier.size(if (isTv) 240.dp else 200.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
    if (isTv) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        qr()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { instructions() }
    } else {
        instructions()
        qr()
    }
}
