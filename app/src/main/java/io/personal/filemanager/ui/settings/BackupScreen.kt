package io.personal.filemanager.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.core.formatFullDate
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.backup.BackupCodec
import io.personal.filemanager.data.backup.RestorePreview
import io.personal.filemanager.data.backup.SecretStore
import io.personal.filemanager.data.settings.BackupMark
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.archive.PasswordDialog
import io.personal.filemanager.ui.components.LoadingBox
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.components.card
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val container = appContainer()
    val backups = container.backups
    val status by container.settings.backupStatus.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    var createDialog by remember { mutableStateOf(false) }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePassword by remember { mutableStateOf<CharArray?>(null) }
    var preview by remember { mutableStateOf<RestorePreview?>(null) }
    var autoDialog by remember { mutableStateOf(false) }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = pendingPassword
        pendingPassword = null
        if (uri == null) {
            pw?.fill('\u0000')
            return@rememberLauncherForActivityResult
        }
        busy = "Создание и проверка копии…"
        scope.launch {
            val r = backups.writeBackup(uri, pw)
            pw?.fill('\u0000')
            busy = null
            r.onSuccess { FileActions.toast(context, "Копия создана и проверена: ${it.name} (${formatSize(it.size)})") }
                .onFailure { FileActions.toast(context, "Копия НЕ создана: ${it.message}") }
        }
    }

    fun loadPreview(uri: Uri, pw: CharArray?) {
        busy = "Проверка копии…"
        scope.launch {
            preview = backups.preview(uri, pw)
            busy = null
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreUri = uri
            restorePassword = null
            loadPreview(uri, null)
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                .onSuccess {
                    scope.launch {
                        val s = container.settings.backupStatusNow()
                        container.settings.setAutoBackup(s.autoEnabled, uri.toString(), s.autoIntervalDays, s.autoKeep, container.settings.autoBackupSecret())
                    }
                }
                .onFailure { FileActions.toast(context, "Не удалось получить постоянный доступ к папке") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Резервные копии", onBack = { navigator.back() })
        val s = status
        if (s == null) {
            LoadingBox()
            return@Column
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "В копию входят заметки, ссылки, избранное (пути, без содержимого файлов) и настройки: плитки, нижняя панель, " +
                    "сортировки. Фото, видео, документы и прочие ваши файлы НЕ копируются. Копия на этом же телефоне не спасёт " +
                    "при его потере — храните хотя бы одну копию на ПК, USB или в личном облаке.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Section("Ручная копия") {
                Text("Последняя проверенная: " + describe(s.lastManual), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                // На узком экране кнопки переносятся целиком, а не по слогам.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { createDialog = true }, enabled = busy == null) { Text("Создать копию", maxLines = 1) }
                    OutlinedButton(onClick = { openLauncher.launch(arrayOf("*/*")) }, enabled = busy == null) { Text("Восстановить", maxLines = 1) }
                }
            }
            Section("Автокопирование") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.autoEnabled) "Включено" else "Выключено", modifier = Modifier.weight(1f))
                    Switch(checked = s.autoEnabled, onCheckedChange = { on ->
                        if (on) {
                            autoDialog = true
                        } else {
                            scope.launch {
                                container.settings.setAutoBackup(false, s.autoFolderUri, s.autoIntervalDays, s.autoKeep, container.settings.autoBackupSecret())
                                backups.cancelAuto()
                            }
                        }
                    })
                }
                if (s.autoEnabled) {
                    Text(
                        "Папка: " + (s.autoFolderUri?.let { Uri.parse(it).lastPathSegment } ?: "не выбрана") +
                            "\nЧастота: " + (if (s.autoIntervalDays == 1) "ежедневно" else "раз в ${s.autoIntervalDays} дн.") +
                            "\nХранить последних: ${s.autoKeep}" +
                            "\nШифрование: " + (if (s.autoEncrypted) "паролем (AES-256)" else "нет"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Последняя автокопия: " + describe(s.lastAuto), style = MaterialTheme.typography.bodyMedium)
                    s.autoError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { treeLauncher.launch(null) }) { Text("Сменить папку") }
                        OutlinedButton(enabled = busy == null, onClick = {
                            busy = "Автокопия…"
                            scope.launch {
                                val r = backups.runAutoBackup()
                                busy = null
                                r.onSuccess { FileActions.toast(context, "Автокопия создана и проверена") }
                                    .onFailure { FileActions.toast(context, it.message ?: "Ошибка") }
                            }
                        }) { Text("Сделать сейчас") }
                    }
                    Text(
                        "Android запускает фоновые задачи по своему расписанию (при достаточном заряде), не в точную минуту. " +
                            "Удаляются только старые автокопии этого приложения в выбранной папке и только после успешной новой.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    busy?.let { text ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(text) }, containerColor = MaterialTheme.colorScheme.surfaceContainer)
    }

    if (createDialog) {
        PasswordChoiceDialog(
            title = "Создать резервную копию",
            onDismiss = { createDialog = false },
            onConfirm = { pw ->
                createDialog = false
                pendingPassword = pw
                createLauncher.launch(backups.suggestedName(auto = false))
            },
        )
    }

    if (autoDialog) {
        AutoBackupDialog(
            onDismiss = { autoDialog = false },
            onConfirm = { interval, keep, pw ->
                autoDialog = false
                scope.launch {
                    val secret = pw?.let { SecretStore.encrypt(it).also { _ -> it.fill('\u0000') } }
                    val current = container.settings.backupStatusNow()
                    container.settings.setAutoBackup(true, current.autoFolderUri, interval, keep, secret)
                    backups.scheduleAuto(interval)
                    if (current.autoFolderUri == null) treeLauncher.launch(null)
                }
            },
        )
    }

    when (val p = preview) {
        null -> Unit
        RestorePreview.PasswordRequired, RestorePreview.WrongPassword -> PasswordDialog(
            wrong = p == RestorePreview.WrongPassword,
            onSubmit = { value ->
                preview = null
                restorePassword?.fill('\u0000')
                restorePassword = value.toCharArray()
                restoreUri?.let { loadPreview(it, restorePassword) }
            },
            onDismiss = { preview = null },
        )
        is RestorePreview.Error -> AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Копию нельзя восстановить") },
            text = { Text(p.message + "\n\nТекущие данные не изменены.") },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Понятно") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        is RestorePreview.Ready -> {
            val m = p.decoded.manifest
            AlertDialog(
                onDismissRequest = { preview = null },
                title = { Text("Восстановить из копии?") },
                text = {
                    Text(
                        "Файл: ${p.sourceName}\nСоздана: ${formatFullDate(m.createdAt)}\nВерсия приложения: ${m.appVersion}" +
                            "\nВ копии: заметок ${m.counts["notes"] ?: 0}, ссылок ${m.counts["links"] ?: 0}, избранного ${m.counts["favorites"] ?: 0}\n\n" +
                            "Текущие заметки, ссылки, избранное и настройки будут ЗАМЕНЕНЫ данными копии. Обычные файлы не затрагиваются. " +
                            "Перед заменой текущие данные сохраняются во внутренний снимок; при ошибке выполняется откат.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val decoded = p.decoded
                        preview = null
                        busy = "Восстановление…"
                        scope.launch {
                            val r = backups.restore(decoded)
                            busy = null
                            restorePassword?.fill('\u0000')
                            restorePassword = null
                            r.onSuccess { FileActions.toast(context, "Данные восстановлены") }
                                .onFailure { FileActions.toast(context, "Восстановление не выполнено, данные возвращены: ${it.message}") }
                        }
                    }) { Text("Восстановить") }
                },
                dismissButton = { TextButton(onClick = { preview = null }) { Text("Отмена") } },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
        }
    }
}

private fun describe(mark: BackupMark?): String =
    mark?.let { "${formatFullDate(it.at)}, ${formatSize(it.size)} (${it.name})" } ?: "ещё не было"

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .card(RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** Пароль по желанию: дважды, с предупреждением о невозможности восстановления без него. */
@Composable
private fun PasswordChoiceDialog(title: String, onDismiss: () -> Unit, onConfirm: (CharArray?) -> Unit, extra: @Composable () -> Unit = {}) {
    var use by remember { mutableStateOf(false) }
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val error = when {
        !use -> null
        p1.length < 6 -> "Не короче 6 символов"
        p1 != p2 -> "Пароли не совпадают"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                extra()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = use, onCheckedChange = { use = it })
                    Text("Зашифровать паролем (AES-256-GCM)")
                }
                if (use) {
                    OutlinedTextField(value = p1, onValueChange = { p1 = it }, singleLine = true, label = { Text("Пароль") },
                        visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(value = p2, onValueChange = { p2 = it }, singleLine = true, label = { Text("Повторите пароль") },
                        visualTransformation = PasswordVisualTransformation(), isError = error != null,
                        supportingText = { error?.let { Text(it) } })
                    Text(
                        "Пароль не сохраняется в файле. Если его забыть, копию восстановить НЕЛЬЗЯ.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "Без пароля файл копии не зашифрован: храните его в надёжном месте.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Формат: .${BackupCodec.FILE_EXTENSION}, версия ${BackupCodec.VERSION}.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = { onConfirm(if (use) p1.toCharArray() else null) }) { Text("Далее") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

@Composable
private fun AutoBackupDialog(onDismiss: () -> Unit, onConfirm: (intervalDays: Int, keep: Int, password: CharArray?) -> Unit) {
    var interval by remember { mutableStateOf(1) }
    var keep by remember { mutableStateOf(7) }
    PasswordChoiceDialog(
        title = "Автокопирование",
        onDismiss = onDismiss,
        onConfirm = { pw -> onConfirm(interval, keep, pw) },
        extra = {
            Text("Как часто:", style = MaterialTheme.typography.labelLarge)
            listOf(1 to "Ежедневно", 7 to "Еженедельно").forEach { (d, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = interval == d, onClick = { interval = d })
                    Text(label)
                }
            }
            Text("Хранить последних копий:", style = MaterialTheme.typography.labelLarge)
            Row {
                listOf(3, 7, 14).forEach { n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = keep == n, onClick = { keep = n })
                        Text("$n")
                    }
                }
            }
            Text(
                "Пароль автокопий хранится на телефоне зашифрованным ключом Android Keystore. Для восстановления на другом " +
                    "телефоне понадобится сам пароль.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        },
    )
}
