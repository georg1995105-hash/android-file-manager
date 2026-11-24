package io.personal.filemanager.ui.hub

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.personal.filemanager.BuildConfig
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.storage.*
import io.personal.filemanager.ui.*
import io.personal.filemanager.ui.components.ScreenTopBar
import kotlinx.coroutines.*
import java.io.File

@Composable
internal fun VaultScreen(shared: List<Uri> = emptyList(), onSharedConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val container = appContainer()
    val nav = LocalNavigator.current
    val tv = isTelevisionUi()
    val store = remember { VaultStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<VaultSession?>(null) }
    var endpoint by remember { mutableStateOf(BuildConfig.PFM_STORAGE_URL) }
    var setupCode by remember { mutableStateOf("") }
    var pair by remember { mutableStateOf<VaultPair?>(null) }
    var approveCode by remember { mutableStateOf("") }
    var approveDialog by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var devices by remember { mutableStateOf<List<VaultDevice>>(emptyList()) }
    var pending by remember { mutableStateOf<List<PendingFile>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var fraction by remember { mutableStateOf<Float?>(null) }
    var removeFile by remember { mutableStateOf<RemoteFile?>(null) }
    var discardFile by remember { mutableStateOf<PendingFile?>(null) }
    var revoke by remember { mutableStateOf<VaultDevice?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var sharedDialog by remember(shared) { mutableStateOf(shared.isNotEmpty()) }
    val name = Build.MODEL.take(80).ifBlank { "Android" }
    fun report(done: Long, total: Long) {
        fraction = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f,1f) else 1f
        info = formatSize(done) + " / " + formatSize(total)
    }
    fun task(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; info = label; fraction = null
        job = scope.launch {
            try { block() }
            catch (e: CancellationException) { info = "Операция остановлена"; throw e }
            catch (e: Exception) { info = e.message?.take(240) ?: "Ошибка хранилища" }
            finally { busy = false; fraction = null }
        }
    }
    suspend fun refresh(s: VaultSession) {
        val client = VaultFiles(context.applicationContext, s)
        pending = withContext(Dispatchers.IO) { client.pending() }
        files = withContext(Dispatchers.IO) { client.list() }
        devices = withContext(Dispatchers.IO) { client.devices() }
    }
    fun send(uris: List<Uri>) {
        val s = session ?: return
        if (uris.isEmpty()) return
        task("Подготовка файлов…") {
            val client = VaultFiles(context.applicationContext, s)
            try {
                withContext(Dispatchers.IO) {
                    for (uri in uris.take(100)) client.enqueue(uri)
                    for (item in client.pending()) client.upload(item, ::report)
                }
                refresh(s); info = "Файлы сохранены в сетевом хранилище"
            } finally { pending = withContext(Dispatchers.IO) { client.pending() } }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { send(it) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { approveCode = it; approveDialog = true }
    }
    LaunchedEffect(Unit) {
        try { session = withContext(Dispatchers.IO) { store.load() } }
        catch (e: Exception) { info = "Не удалось открыть сохранённую привязку" }
        finally { loaded = true }
    }
    LaunchedEffect(session?.device) {
        val s = session ?: return@LaunchedEffect
        task("Получение файлов…") {
            val client = VaultFiles(context.applicationContext, s)
            try {
                withContext(Dispatchers.IO) {
                    for (item in client.pending()) client.upload(item, ::report)
                }
                refresh(s); info = ""
            } finally { pending = withContext(Dispatchers.IO) { client.pending() } }
        }
    }
    LaunchedEffect(pair) {
        val request = pair ?: return@LaunchedEffect
        try {
            val result = withTimeout(5 * 60 * 1000L) {
                var current: VaultSession? = null
                while (current == null) {
                    current = withContext(Dispatchers.IO) { VaultPairing.claim(request) }
                    if (current == null) delay(2000)
                }
                current
            }
            withContext(Dispatchers.IO) { store.save(result) }
            session = result; pair = null; info = "Устройство привязано"
        } catch (_: TimeoutCancellationException) { pair = null; info = "Код истёк. Создайте новый." }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { pair = null; info = e.message ?: "Не удалось привязать устройство" }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Личное хранилище", onBack = { nav.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!loaded) { CircularProgressIndicator(); return@Column }
            val s = session
            if (s == null) {
                Text("Подключение к личному хранилищу", style = MaterialTheme.typography.titleLarge)
                Text("Файлы сохраняются на сервере в зашифрованном виде и доступны вашим привязанным устройствам.")
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS-адрес сервера") },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy && pair == null, singleLine = true)
                if (pair == null) {
                    Button(onClick = { task("Создание кода…") {
                        pair = withContext(Dispatchers.IO) { VaultPairing.begin(endpoint, name) }
                        info = "Отсканируйте код на уже привязанном телефоне"
                    } }, enabled = !busy && endpoint.isNotBlank()) { Text("Привязать устройство") }
                    if (!tv) {
                        HorizontalDivider()
                        Text("Первоначальная настройка", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(setupCode, { setupCode = it }, label = { Text("Код владельца") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy,
                            modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && endpoint.isNotBlank() && setupCode.isNotBlank(),
                            onClick = { task("Подключение…") {
                                val newSession = withContext(Dispatchers.IO) {
                                    VaultPairing.bootstrap(endpoint, setupCode.trim(), name).also(store::save)
                                }
                                setupCode = ""; session = newSession; info = "Хранилище подключено"
                            } }) { Text("Подключить как владелец") }
                    }
                } else {
                    val code = pair!!.code
                    Text("Откройте хранилище на привязанном телефоне → «Сканировать QR».")
                    val bitmap = remember(code) {
                        val matrix = MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 320, 320)
                        Bitmap.createBitmap(IntArray(320 * 320) { i ->
                            if (matrix[i % 320, i / 320]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                        }, 320, 320, Bitmap.Config.ARGB_8888).asImageBitmap()
                    }
                    Image(bitmap, "QR привязки устройства", modifier = Modifier.size(240.dp))
                    Text(code, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { pair = null }) { Text("Отмена") }
                }
            } else {
                Text("Ваше хранилище", style = MaterialTheme.typography.titleLarge)
                Text("Файлы доступны независимо от того, включён ли телевизор.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!tv) Button(enabled = !busy, onClick = {
                        runCatching { picker.launch(arrayOf("*/*")) }.onFailure { info = "Нет приложения для выбора файлов" }
                    }) { Text("Отправить файлы") }
                    OutlinedButton(enabled = !busy, onClick = { task("Обновление…") { refresh(s); info = "" } }) { Text("Обновить") }
                }
                if (pending.isNotEmpty()) {
                    Text("Ожидают отправки: ${pending.size}", style = MaterialTheme.typography.titleMedium)
                    pending.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.name + " · " + formatSize(item.size), Modifier.weight(1f))
                            TextButton(enabled = !busy, onClick = { discardFile = item }) { Text("Отменить") }
                        }
                    }
                    Button(enabled = !busy, onClick = { task("Продолжение отправки…") {
                        val client = VaultFiles(context.applicationContext, s)
                        try { withContext(Dispatchers.IO) { for (item in client.pending()) client.upload(item, ::report) }; refresh(s); info = "Отправлено" }
                        finally { pending = withContext(Dispatchers.IO) { client.pending() } }
                    } }) { Text("Продолжить") }
                }
                Text("Файлы · ${files.size}", style = MaterialTheme.typography.titleMedium)
                if (files.isEmpty()) Text("Нет полученных файлов или список ещё не загружен.")
                files.forEach { file ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.titleMedium)
                                Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(enabled = !busy, onClick = { task("Скачивание…") {
                                val result = withContext(Dispatchers.IO) {
                                    VaultFiles(context.applicationContext,s).download(file, container.transfer.receivedDirectory, ::report)
                                }
                                container.transfer.rescan(result.absolutePath)
                                info = "Файл сохранён"
                                nav.openFile(FileEntry.from(result))
                            } }) { Text("Открыть") }
                            IconButton(enabled = !busy, onClick = { removeFile = file }) { Icon(Icons.Filled.Delete, "Удалить из хранилища") }
                        }
                    }
                }
                HorizontalDivider()
                Text("Привязанные устройства", style = MaterialTheme.typography.titleMedium)
                val owner = devices.any { it.id == s.device && it.owner }
                devices.forEach { d ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name + if (d.id == s.device) " · Это устройство" else "", Modifier.weight(1f))
                        if (owner && !d.owner) TextButton(enabled = !busy, onClick = { revoke = d }) { Text("Отвязать") }
                    }
                }
                if (!tv) {
                    OutlinedButton(enabled = !busy, onClick = {
                        runCatching { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Сканируйте QR на новом устройстве").setBeepEnabled(false)) }
                            .onFailure { approveDialog = true }
                    }) { Text("Сканировать QR") }
                    TextButton(enabled = !busy, onClick = { approveDialog = true }) { Text("Ввести код вручную") }
                }
            }
            if (busy) {
                fraction?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
                    ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { job?.cancel() }) { Text("Остановить") }
            }
            if (info.isNotBlank()) Text(info, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (approveDialog && session != null) AlertDialog(onDismissRequest = { approveDialog = false; approveCode = "" },
        title = { Text("Привязать новое устройство") },
        text = { OutlinedTextField(approveCode, { approveCode = it }, label = { Text("Одноразовый код с устройства") }) },
        confirmButton = { TextButton(enabled = approveCode.isNotBlank(), onClick = {
            val code = approveCode; val s = session!!
            approveDialog = false; approveCode = ""
            task("Привязка…") { withContext(Dispatchers.IO) { VaultPairing.approve(s,code) }; info = "Устройство одобрено"; refresh(s) }
        }) { Text("Привязать") } },
        dismissButton = { TextButton(onClick = { approveDialog = false; approveCode = "" }) { Text("Отмена") } })
    discardFile?.let { item -> AlertDialog(onDismissRequest = { discardFile = null },
        title = { Text("Отменить отправку?") }, text = { Text(item.name + " · Незавершённая копия будет удалена.") },
        confirmButton = { TextButton(onClick = { discardFile = null; session?.let { s -> task("Отмена отправки…") {
            val client = VaultFiles(context.applicationContext,s)
            withContext(Dispatchers.IO) { client.discard(item) }
            refresh(s); info = "Отправка отменена"
        } } }) { Text("Отменить отправку") } },
        dismissButton = { TextButton(onClick = { discardFile = null }) { Text("Назад") } }) }
    removeFile?.let { file -> AlertDialog(onDismissRequest = { removeFile = null },
        title = { Text("Удалить файл из хранилища?") }, text = { Text(file.name + " · Удаление затронет все устройства.") },
        confirmButton = { TextButton(onClick = { removeFile = null; session?.let { s -> task("Удаление…") {
            withContext(Dispatchers.IO) { VaultFiles(context.applicationContext,s).delete(file) }; refresh(s); info = "Удалено"
        } } }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { removeFile = null }) { Text("Отмена") } }) }
    revoke?.let { d -> AlertDialog(onDismissRequest = { revoke = null }, title = { Text("Отвязать устройство?") },
        text = { Text(d.name) }, confirmButton = { TextButton(onClick = { revoke = null; session?.let { s -> task("Отвязка…") {
            withContext(Dispatchers.IO) { VaultFiles(context.applicationContext,s).revoke(d) }; refresh(s); info = "Доступ отключён"
        } } }) { Text("Отвязать") } }, dismissButton = { TextButton(onClick = { revoke = null }) { Text("Отмена") } }) }
    if (sharedDialog && shared.isNotEmpty() && loaded && session != null && !busy) AlertDialog(
        onDismissRequest = { sharedDialog = false; onSharedConsumed() }, title = { Text("Отправить в личное хранилище?") },
        text = { Text("Файлов: ${shared.size}. Они будут доступны привязанным устройствам.") },
        confirmButton = { TextButton(onClick = { sharedDialog = false; send(shared); onSharedConsumed() }) { Text("Отправить") } },
        dismissButton = { TextButton(onClick = { sharedDialog = false; onSharedConsumed() }) { Text("Отмена") } })
}
