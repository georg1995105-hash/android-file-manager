package io.personal.filemanager.ui.transfer

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.transfer.TransferClient
import io.personal.filemanager.data.transfer.TransferManager
import kotlinx.coroutines.*
import org.json.JSONObject

/** Найденные устройства: одно нажатие — подключиться и сразу отправить выбранное; затем можно скачать их файлы. */
@Composable
fun TransferPeerPanel(manager: TransferManager) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("transfer-trusted-certificates", Context.MODE_PRIVATE) }
    val peers by manager.discovery.peers.collectAsStateWithLifecycle()
    val own by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var client by remember { mutableStateOf<TransferClient?>(null) }
    var connectedName by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun launch(action: suspend () -> Unit) {
        if (busy) return
        runCatching { context.startForegroundService(android.content.Intent(context, io.personal.filemanager.data.transfer.TransferService::class.java)) }
            .onFailure { message = "Не удалось включить фоновую передачу: ${it.message}"; return }
        busy = true
        job = scope.launch(Dispatchers.IO) {
            try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = e.message ?: "Ошибка подключения" }
            finally { busy = false }
        }
    }
    suspend fun refreshFiles(peer: TransferClient) {
        val list = peer.api("/files").getJSONArray("files")
        files = (0 until list.length()).map { list.getJSONObject(it) }
    }
    suspend fun sendSelected(peer: TransferClient, name: String) {
        val selected = manager.selectedFiles()
        if (selected.isEmpty()) return
        peer.beginBatch()
        selected.forEachIndexed { i, item -> peer.send(item) { message = "${i + 1} из ${selected.size}: $it" } }
        message = "Отправлено на «$name»: ${selected.size}"
    }
    suspend fun connect(name: String, rawAddress: String) {
        message = "Подключение к «$name»…"
        val probe = TransferClient(rawAddress)
        val fingerprint = probe.inspect()
        // Первое подключение запоминает сертификат устройства; если у того же устройства он сменился — останавливаемся.
        val known = prefs.getString(name, null)
        if (known != null && known != fingerprint) {
            error("Сертификат «$name» изменился. Если приложение там переустанавливали — нажмите «Забыть устройства» и подключитесь снова.")
        }
        val peer = TransferClient(probe.base, fingerprint)
        client = peer; connectedName = name; files = emptyList()
        message = "Нажмите «Разрешить» на «$name»"
        peer.pair("", Build.MODEL)
        prefs.edit().putString(name, fingerprint).apply()
        message = "Подключено к «$name»"
        sendSelected(peer, name)
        refreshFiles(peer)
    }
    DisposableEffect(Unit) {
        manager.discovery.discover()
        onDispose { client?.cancel(); job?.cancel(); manager.discovery.stopDiscovery() }
    }
    val visible = peers.filterNot { it.address in own.addresses }
    val connected = client?.token?.isNotEmpty() == true

    Text("Устройства рядом", style = MaterialTheme.typography.titleLarge)
    if (visible.isEmpty()) {
        Text("Ищем… Откройте «Передачу» на другом устройстве в той же Wi-Fi сети.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    visible.forEach { peer ->
        val name = peer.name.removePrefix("Мои файлы ").ifBlank { peer.name }
        Button(enabled = !busy, onClick = { launch { connect(name, peer.address) } }, modifier = Modifier.fillMaxWidth()) {
            Text(if (own.shared > 0) "Отправить на «$name»" else "Подключиться к «$name»")
        }
    }
    TextButton(enabled = !busy, onClick = { manual = !manual }) { Text(if (manual) "Скрыть ввод адреса" else "Устройства нет в списке? Ввести адрес") }
    if (manual) {
        OutlinedTextField(address, { address = it }, enabled = !busy, label = { Text("Адрес с экрана другого устройства") },
            placeholder = { Text("https://192.168.0.10:40000") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && address.isNotBlank(), onClick = {
            launch { connect(address.trim().removePrefix("https://").substringBefore(':'), address) }
        }) { Text("Подключиться") }
    }
    if (connected) {
        Text("Файлы на «$connectedName»", style = MaterialTheme.typography.titleMedium)
        if (files.isEmpty()) Text("На том устройстве ничего не выбрано для отправки.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        files.forEach { file ->
            OutlinedButton(enabled = !busy, onClick = {
                launch { client!!.receive(file, manager) { message = it }; message = "«${file.optString("name")}» — в «Полученных файлах»" }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Скачать «${file.optString("name")}» (${formatSize(file.optLong("size"))})")
            }
        }
        Row {
            TextButton(enabled = !busy, onClick = { launch { refreshFiles(client!!) } }) { Text("Обновить список") }
            if (own.shared > 0) TextButton(enabled = !busy, onClick = { launch { sendSelected(client!!, connectedName) } }) { Text("Отправить выбранные (${own.shared})") }
        }
    }
    if (busy) OutlinedButton(onClick = { client?.cancel(); job?.cancel(); busy = false; message = "Отменено" }) { Text("Отменить") }
    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.titleSmall)
    if (prefs.all.isNotEmpty()) {
        TextButton(enabled = !busy, onClick = { prefs.edit().clear().apply(); message = "Запомненные устройства забыты" }) { Text("Забыть устройства") }
    }
}
