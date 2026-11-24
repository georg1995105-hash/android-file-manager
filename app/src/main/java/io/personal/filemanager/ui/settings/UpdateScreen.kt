package io.personal.filemanager.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.personal.filemanager.BuildConfig
import io.personal.filemanager.data.updates.OtaRelease
import io.personal.filemanager.data.updates.OtaUpdater
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.components.ScreenTopBar
import kotlinx.coroutines.launch
import java.io.File

/** TV remote-friendly controls; Android package installer handles confirmation. */
@Composable
fun UpdateScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val updater = remember(context) { OtaUpdater(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Проверка обновлений…") }
    var release by remember { mutableStateOf<OtaRelease?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableStateOf(-1) }
    val check: () -> Unit = {
        scope.launch {
            loading = true
            message = "Проверка обновлений…"
            downloaded = null
            try {
                release = updater.latest()
                message = if (release == null) "Установлена актуальная опубликованная версия"
                          else "Доступно обновление: ${release!!.version}"
            } catch (e: Exception) {
                message = "Не удалось проверить: ${e.message ?: "ошибка сети"}"
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { check() }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Обновления", onBack = { navigator.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Мои файлы ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
            Text(message, style = MaterialTheme.typography.bodyLarge)
            if (loading && progress >= 0) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Загружено: $progress%")
            }
            TextButton(onClick = check, enabled = !loading) { Text("Проверить обновления") }
            val candidate = release
            if (candidate != null) {
                Button(
                    enabled = !loading,
                    onClick = {
                        val file = downloaded
                        if (file != null && file.isFile) {
                            try {
                                message = if (updater.install(file)) "Подтверди установку в Android"
                                    else "Разреши установку из этого источника и снова нажми «Установить»"
                            } catch (e: Exception) { message = "Ошибка установщика: ${e.message}" }
                        } else {
                            scope.launch {
                                loading = true
                                progress = -1
                                message = "Загрузка и проверка APK…"
                                try {
                                    downloaded = updater.download(candidate) { progress = it }
                                    message = if (updater.install(downloaded!!)) "Подтверди установку в Android"
                                        else "Разреши установку из этого источника и снова нажми «Установить»"
                                } catch (e: Exception) {
                                    downloaded = null
                                    message = "Обновление остановлено: ${e.message ?: "ошибка"}"
                                } finally { loading = false }
                            }
                        }
                    },
                ) { Text(if (downloaded == null) "Скачать и обновить" else "Установить") }
            }
            Text(
                "Данные приложения сохраняются. Используются только опубликованные стабильные " +
                    "APK с исходной подписью и проверенной SHA-256.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
