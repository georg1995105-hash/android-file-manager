package io.personal.filemanager.ui.transfer

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.data.transfer.TransferClient
import io.personal.filemanager.data.transfer.TransferManager
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun TransferPeerPanel(manager: TransferManager) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("transfer-trusted-certificates", Context.MODE_PRIVATE) }
    val peers by manager.discovery.peers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var rememberTrust by remember { mutableStateOf(false) }
    var inspected by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<TransferClient?>(null) }
    var files by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var receive by remember { mutableStateOf<JSONObject?>(null) }
    fun launch(action: suspend () -> Unit) {
        if (busy) return
        runCatching { context.startForegroundService(android.content.Intent(context, io.personal.filemanager.data.transfer.TransferService::class.java)) }
            .onFailure { message = "Не удалось включить фоновую передачу: ${it.message}"; return }
        busy = true
        job = scope.launch(Dispatchers.IO) {
            try { action() } catch (e: Exception) { message = e.message ?: "Ошибка подключения" }
            finally { busy = false }
        }
    }
    suspend fun connect() {
        val peer = TransferClient(inspected, fingerprint)
        client = peer
        message = "Подтвердите подключение на другом устройстве"
        peer.pair(code, Build.MODEL)
        val list = peer.api("/files").getJSONArray("files")
        files = (0 until list.length()).map { list.getJSONObject(it) }
        message = "Подключено"
        if (rememberTrust) prefs.edit().putString(inspected, fingerprint).apply()
    }
    DisposableEffect(Unit) {
        manager.discovery.discover()
        onDispose { client?.cancel(); job?.cancel(); manager.discovery.stopDiscovery() }
    }
    HorizontalDivider()
    Text("Подключиться к другому устройству", style = MaterialTheme.typography.titleLarge)
    peers.forEach { peer -> TextButton(enabled = !busy, onClick = { address = peer.address; fingerprint = ""; client = null; files = emptyList() }) { Text(peer.name + " · " + peer.address) } }
    OutlinedTextField(address, { address = it; fingerprint = ""; client = null; files = emptyList() }, enabled = !busy, label = { Text("https://IP:порт") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(code, { code = it }, enabled = !busy, label = { Text("Код другого устройства") }, singleLine = true)
    Button(enabled = !busy && address.isNotBlank(), onClick = { launch {
        val probe = TransferClient(address)
        inspected = probe.base
        fingerprint = probe.inspect()
        if (prefs.getString(inspected, null) == fingerprint) connect() else message = "Сверьте отпечаток на обоих устройствах"
    } }) { Text("Проверить устройство") }
    if (fingerprint.isNotBlank() && client?.token.isNullOrEmpty()) {
        SelectionContainer { Text(fingerprint, style = MaterialTheme.typography.bodySmall) }
        Text("Продолжайте только если SHA-256 совпадает с показанным на другом устройстве.")
        Row { Checkbox(rememberTrust, { rememberTrust = it }); Text("Запомнить это устройство") }
        Button(enabled = !busy, onClick = { launch { connect() } }) { Text("Отпечаток совпадает — подключить") }
    }
    if (!client?.token.isNullOrEmpty()) {
        Button(enabled = !busy, onClick = { launch {
            val selected = manager.selectedFiles()
            require(selected.isNotEmpty()) { "Сначала выберите файлы → Передать" }
            client!!.beginBatch()
            for (item in selected) client!!.send(item) { message = it }
        } }) { Text("Отправить выбранные файлы") }
        TextButton(enabled = !busy, onClick = { launch {
            val list = client!!.api("/files").getJSONArray("files")
            files = (0 until list.length()).map { list.getJSONObject(it) }
        } }) { Text("Обновить файлы другого устройства") }
        files.forEach { file -> Button(enabled = !busy, onClick = { receive = file }) { Text("Получить ${file.optString("name")} (${file.optLong("size")} байт)") } }
    }
    if (busy) Button(onClick = { client?.cancel(); job?.cancel(); message = "Операция отменена" }) { Text("Отменить операцию") }
    TextButton(enabled = !busy, onClick = { prefs.edit().clear().apply(); message = "Доверенные сертификаты удалены" }) { Text("Забыть доверенные устройства") }
    Text(message)
    receive?.let { file -> AlertDialog(onDismissRequest = { receive = null }, title = { Text("Принять файл?") }, text = { Text(file.optString("name")) },
        confirmButton = { TextButton(onClick = { receive = null; launch { client!!.receive(file, manager) { message = it } } }) { Text("Принять") } },
        dismissButton = { TextButton(onClick = { receive = null }) { Text("Отмена") } }) }
}
