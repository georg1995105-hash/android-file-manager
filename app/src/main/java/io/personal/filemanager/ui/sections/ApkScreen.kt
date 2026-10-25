package io.personal.filemanager.ui.sections

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.personal.filemanager.core.FileKind
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.ApkInfo
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.InstalledVersion
import io.personal.filemanager.data.apps.ApkSetCheck
import io.personal.filemanager.data.apps.ApkSets
import io.personal.filemanager.data.apps.PackageInstall
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.components.KindBadge
import io.personal.filemanager.ui.components.LoadingBox
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.components.card
import io.personal.filemanager.ui.components.toImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import java.io.File

private data class ApkDetails(
    val info: ApkInfo?,
    val icon: ImageBitmap?,
    val installed: InstalledVersion?,
    val set: ApkSetCheck?,
)

/**
 * Карточка APK или набора split APK: имя, пакет, версия, сравнение с установленной;
 * установка только через системный установщик с подтверждением пользователя.
 */
@Composable
fun ApkScreen(path: String) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val apps = appContainer().apps
    val file = remember(path) { File(path) }
    val isSet = file.extension.lowercase() in setOf("apks", "xapk", "apkm")
    var details by remember { mutableStateOf<ApkDetails?>(null) }
    var canInstall by remember { mutableStateOf(PackageInstall.canRequestInstalls(context)) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(Unit) {
        canInstall = PackageInstall.canRequestInstalls(context)
        onPauseOrDispose { }
    }

    LaunchedEffect(path) {
        details = withContext(Dispatchers.IO) {
            if (!isSet) {
                val parsed = apps.apkArchive(path)
                ApkDetails(parsed?.first, parsed?.second?.toImage(), parsed?.first?.packageName?.let(apps::installedVersion), null)
            } else {
                val check = ApkSets.inspect(file)
                // Для сведений о версии читаем базовый APK из набора во временный файл.
                var info: Pair<ApkInfo, android.graphics.drawable.Drawable?>? = null
                if (check is ApkSetCheck.Ok) {
                    val tmp = File(context.cacheDir, "apk-inspect/base.apk").apply { parentFile?.mkdirs() }
                    runCatching {
                        ZipFile(file).use { z ->
                            z.getInputStream(z.getFileHeader(check.info.baseEntry)).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        }
                        info = apps.apkArchive(tmp.path)
                    }
                    tmp.delete()
                }
                ApkDetails(info?.first, info?.second?.toImage(), info?.first?.packageName?.let(apps::installedVersion), check)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = file.name, subtitle = formatSize(file.length()), onBack = { navigator.back() })
        val d = details
        if (d == null) {
            LoadingBox()
            return@Column
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .card(RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = d.icon
                if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(56.dp)) else KindBadge(FileKind.APK, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(d.info?.label ?: "Неизвестное приложение", style = MaterialTheme.typography.titleMedium)
                    Text(d.info?.packageName ?: "пакет не прочитан", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    d.info?.let { Text("Версия ${it.versionName ?: "?"} (${it.versionCode})", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Spacer(Modifier.height(12.dp))
            val installedText = when {
                d.info == null -> null
                d.installed == null -> "На телефоне не установлено (или скрыто от нашего приложения)."
                d.installed.versionCode == d.info.versionCode -> "Установлена та же версия ${d.installed.versionName}."
                d.installed.versionCode < d.info.versionCode -> "Установлена версия ${d.installed.versionName} — это обновление."
                else -> "Установлена более новая версия ${d.installed.versionName}: Android не разрешит понизить версию без удаления."
            }
            installedText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            d.info?.takeIf { it.minSdk > 0 }?.let { Text("Минимальный Android: API ${it.minSdk}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (d.info == null && !isSet) {
                Text("Android не смог прочитать этот APK: файл повреждён или не является APK.", color = MaterialTheme.colorScheme.error)
            }
            when (val s = d.set) {
                is ApkSetCheck.Ok -> {
                    Spacer(Modifier.height(12.dp))
                    Text("Набор из ${s.info.apkEntries.size} APK, ${formatSize(s.info.totalBytes)}:", style = MaterialTheme.typography.titleSmall)
                    s.info.apkEntries.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    if (s.info.hasObb) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "В наборе есть данные OBB — они не устанавливаются автоматически (Android закрывает Android/obb).",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is ApkSetCheck.Invalid -> Text(s.reason, color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Установка выполняется системным установщиком Android после вашего подтверждения; проверки Android и Play Protect не отключаются.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!canInstall) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Чтобы предложить установку, Android требует разрешить этому приложению «Установку неизвестных приложений».",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { PackageInstall.openUnknownSourcesSettings(context) }) { Text("Открыть настройку") }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { FileActions.share(context, listOf(FileEntry.from(file))) }, modifier = Modifier.weight(1f)) {
                Text("Поделиться")
            }
            val setOk = d.set as? ApkSetCheck.Ok
            Button(
                enabled = canInstall && !busy && (if (isSet) setOk != null else d.info != null),
                onClick = {
                    if (!isSet) {
                        FileActions.openExternally(context, FileEntry.from(file))
                    } else if (setOk != null) {
                        busy = true
                        scope.launch {
                            val error = withContext(Dispatchers.IO) { PackageInstall.installSet(context, file, setOk.info) }
                            busy = false
                            if (error != null) FileActions.toast(context, error)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (busy) "Подготовка…" else "Установить") }
        }
    }
}
