package io.personal.filemanager.ui.transfer

import android.content.Intent
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
    var details by rememberSaveable { mutableStateOf(false) }
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
        if (state.progress.isNotBlank()) Text(state.progress)
        if (state.message.isNotBlank()) Text(state.message)
        Button(onClick = { manager.receivedDirectory.mkdirs(); nav.open(Routes.browser(manager.receivedDirectory.absolutePath)) }) {
            Text("Полученные файлы")
        }
        HorizontalDivider()
        if (state.running) {
            Text("С компьютера", style = MaterialTheme.typography.titleLarge)
            val address = state.addresses.firstOrNull()
            if (address == null) {
                Text("Нет локального адреса. Подключитесь к Wi-Fi и перезапустите передачу.")
            } else {
                Text("Отсканируйте QR камерой или откройте адрес в браузере компьютера, затем разрешите подключение здесь.")
                val bitmap = remember(address) {
                    val matrix = com.google.zxing.MultiFormatWriter().encode(address, com.google.zxing.BarcodeFormat.QR_CODE, 256, 256)
                    android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888).apply {
                        for (y in 0 until 256) for (x in 0 until 256) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
                androidx.compose.foundation.Image(bitmap.asImageBitmap(), "QR подключения", Modifier.size(200.dp))
                SelectionContainer { Text(address, style = MaterialTheme.typography.titleMedium) }
            }
            OutlinedButton(onClick = { context.stopService(Intent(context, TransferService::class.java)) }) { Text("Остановить передачу") }
        } else {
            Button(onClick = start) { Text("Включить передачу") }
        }
        TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть сведения о безопасности" else "Сведения о безопасности") }
        if (details) {
            Text(
                "Трафик шифруется (HTTPS), интернет не используется. Подключение и приём файлов разрешаются кнопкой на этом " +
                    "устройстве. Браузер при первом подключении предупредит о локальном сертификате — это ожидаемо. " +
                    "Отпечаток сертификата этого устройства (SHA-256):",
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionContainer { Text(state.fingerprint, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = nav::back) { Text("Назад") }
    }
}
