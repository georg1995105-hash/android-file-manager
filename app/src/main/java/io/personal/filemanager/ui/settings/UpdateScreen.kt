package io.personal.filemanager.ui.settings

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.personal.filemanager.BuildConfig
import io.personal.filemanager.data.updates.OtaRelease
import io.personal.filemanager.data.updates.OtaUpdater
import io.personal.filemanager.data.updates.OtaInstallSession
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.isTelevisionUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** One download, system confirmation, and explicit retry without downloading again. */
@Composable
fun UpdateScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val updater = remember(context) { OtaUpdater(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val tv = isTelevisionUi()
    val focus = remember { FocusRequester() }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Проверка обновлений…") }
    var release by remember { mutableStateOf<OtaRelease?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableStateOf(-1) }
    var fallback by remember { mutableStateOf(false) }
    var direct by remember { mutableStateOf(false) }
    val event by OtaInstallSession.events.collectAsState()
    val confirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        loading = false
        if (it.resultCode != Activity.RESULT_OK) {
            message = "Установка не завершена. Нажмите «Установить» для повтора."
            fallback = true
        }
    }

    fun launchInstall(file: File, candidate: OtaRelease, useDirect: Boolean = false) {
        scope.launch {
            loading = true
            progress = -1
            message = "Подготовка установки…"
            try {
                if (useDirect) {
                    confirmation.launch(updater.installerIntent(file, candidate))
                    message = "Подтвердите обновление в системном окне Android"
                    loading = false
                } else {
                    updater.install(file, candidate)
                    message = "Открываем системное подтверждение…"
                    // Keep the button available if a vendor installer never sends a callback.
                    loading = false
                    fallback = true
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                message = "Ошибка установки: ${e.message ?: e.javaClass.simpleName}"
                loading = false
                fallback = true
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = downloaded
        val candidate = release
        if (context.packageManager.canRequestPackageInstalls() && file != null && candidate != null)
            launchInstall(file, candidate, direct)
        else {
            loading = false
            message = "Разрешите установку приложений из «Передачи», затем нажмите «Установить»."
        }
    }
    fun install(file: File, candidate: OtaRelease, useDirect: Boolean = false) {
        direct = useDirect
        if (context.packageManager.canRequestPackageInstalls()) {
            launchInstall(file, candidate, useDirect)
        } else {
            message = "Разрешите установку из «Передачи» — затем обновление продолжится."
            try {
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } catch (_: Exception) {
                try { permission.launch(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                catch (e: Exception) { message = "Не удалось открыть настройки разрешений: ${e.message}" }
            }
        }
    }

    LaunchedEffect(event) {
        val result = event ?: return@LaunchedEffect
        OtaInstallSession.events.compareAndSet(result, null)
        when (result.status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                try {
                    confirmation.launch(checkNotNull(result.confirmation))
                    message = "Подтвердите обновление в системном окне Android"
                } catch (e: Exception) {
                    message = "Не удалось открыть установщик: ${e.message}"
                    fallback = true
                }
                loading = false
            }
            PackageInstaller.STATUS_SUCCESS -> {
                loading = false
                message = "Обновление установлено"
            }
            else -> {
                loading = false
                message = if (result.status == PackageInstaller.STATUS_FAILURE_ABORTED) "Установка отменена. Можно повторить без скачивания."
                    else "Установка не выполнена: ${result.message.ifBlank { "код ${result.status}" }}"
                fallback = true
            }
        }
    }

    val check: () -> Unit = {
        scope.launch {
            loading = true
            progress = -1
            message = "Проверка обновлений…"
            try {
                release = updater.latest()
                downloaded = release?.let { updater.cached(it) }
                message = when {
                    release == null -> "Установлена актуальная опубликованная версия"
                    downloaded != null -> "Обновление ${release!!.version} скачано. Нажмите «Установить»."
                    else -> "Доступно обновление: ${release!!.version}"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "Не удалось проверить: ${e.message ?: "ошибка сети"}" }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { check() }
    LaunchedEffect(tv, loading, release, downloaded) {
        if (tv && !loading && release != null) runCatching { focus.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Обновления", onBack = { navigator.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Передача ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
            Text(message, style = MaterialTheme.typography.bodyLarge)
            if (loading && progress >= 0) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Загружено: $progress%")
            }
            val candidate = release
            if (candidate != null) {
                Button(modifier = Modifier.fillMaxWidth().focusRequester(focus), enabled = !loading, onClick = {
                    val file = downloaded
                    if (file != null && file.isFile) install(file, candidate)
                    else scope.launch {
                        loading = true
                        progress = -1
                        message = "Загрузка и проверка APK…"
                        try {
                            val verified = updater.download(candidate) { progress = it }
                            downloaded = verified
                            loading = false
                            install(verified, candidate)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            downloaded = null
                            message = "Обновление остановлено: ${e.message ?: "ошибка"}"
                        } finally { if (downloaded == null) loading = false }
                    }
                }) { Text(if (downloaded == null) "Скачать и обновить" else "Установить") }
                if (fallback && downloaded?.isFile == true) TextButton(enabled = !loading, onClick = {
                    downloaded?.let { install(it, candidate, true) }
                }) { Text("Открыть APK системным установщиком") }
            }
            TextButton(onClick = check, enabled = !loading) { Text("Проверить обновления") }
            Text("Обновление устанавливается поверх текущей версии. Данные сохраняются.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
