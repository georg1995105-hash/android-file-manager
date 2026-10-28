package io.personal.filemanager.ui.transfer

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.data.transfer.TransferService
import io.personal.filemanager.ui.*

@Composable
fun TransferScreen() {
    val manager = appContainer().transfer
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Передача", style = MaterialTheme.typography.headlineMedium)
        Text("Оба устройства должны находиться в одной локальной сети Wi-Fi или Ethernet.")
        if (!state.running) {
            Button(onClick = { runCatching { context.startForegroundService(Intent(context, TransferService::class.java)) }.onFailure { manager.message(it.message ?: "Ошибка запуска") } }) { Text("Включить передачу") }
        } else {
            Button(onClick = { context.stopService(Intent(context, TransferService::class.java)) }) { Text("Остановить передачу") }
            SelectionContainer { Text(state.addresses.joinToString("\n").ifBlank { "Нет локального IPv4-адреса. Подключитесь к сети и перезапустите передачу." }) }
            state.addresses.firstOrNull()?.let { address ->
                val bitmap = remember(address, state.code) {
                    val matrix = com.google.zxing.MultiFormatWriter().encode(address + "#code=" + state.code, com.google.zxing.BarcodeFormat.QR_CODE, 256, 256)
                    android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888).apply {
                        for (y in 0 until 256) for (x in 0 until 256) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
                androidx.compose.foundation.Image(bitmap.asImageBitmap(), "QR подключения", Modifier.size(200.dp))
            }
            SelectionContainer { Text("Код: ${state.code}", style = MaterialTheme.typography.headlineSmall) }
            TextButton(onClick = manager::renewCode) { Text("Новый код (2 минуты)") }
            Text("На другом устройстве откройте адрес в браузере. При первом подключении браузер предупредит о локальном сертификате. Перед принятием сверьте его SHA-256:")
            SelectionContainer { Text(state.fingerprint, style = MaterialTheme.typography.bodySmall) }
            Text("HTTPS защищает трафик. Без сверки сертификата первое подключение не защищено от подмены устройства в сети.")
        }
        Text("Для отправки выберите файлы в обычном списке → «Передать». Другой участник получит доступ только к выбранным файлам.")
        Text("Выбрано файлов: ${state.shared}")
        if (state.shared > 0) TextButton(onClick = manager::clearShared) { Text("Закрыть доступ к выбранным файлам") }
        Button(onClick = { manager.receivedDirectory.mkdirs(); nav.open(Routes.browser(manager.receivedDirectory.absolutePath)) }) { Text("Полученные файлы") }
        state.approvals.forEach { request ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(request.description)
                    Row {
                        Button(onClick = { manager.approve(request.id, true) }) { Text("Разрешить") }
                        TextButton(onClick = { manager.approve(request.id, false) }) { Text("Отклонить") }
                    }
                }
            }
        }
        manager.activeReceives().forEach { active ->
            TextButton(onClick = { manager.cancel(active.id) }) { Text("Отменить приём: ${active.description}") }
        }
        TransferPeerPanel(manager)
        Text(state.progress)
        Text(state.message)
        TextButton(onClick = nav::back) { Text("Назад") }
    }
}
