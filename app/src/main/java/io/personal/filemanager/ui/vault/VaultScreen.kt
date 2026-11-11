package io.personal.filemanager.ui.vault

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.WindowManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.vault.PrivateVault
import io.personal.filemanager.data.vault.VaultItem
import io.personal.filemanager.data.vault.VaultKind
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.theme.AppColors
import io.personal.filemanager.ui.viewer.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * No faux private folders: media/text are encrypted in application-private no-backup storage.
 * This screen stays FLAG_SECURE and locks on background. Import never deletes the original.
 */
@Composable
fun VaultScreen() {
    val context = LocalContext.current
    val activity = context.findActivity()
    val navigator = LocalNavigator.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val vault = remember(context.applicationContext) { PrivateVault(context.applicationContext) }

    var configured by remember { mutableStateOf(vault.isConfigured()) }
    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var currentKind by remember { mutableStateOf<VaultKind?>(null) }
    var editing by remember { mutableStateOf<VaultItem?>(null) }
    var editingKind by remember { mutableStateOf<VaultKind?>(null) }
    var editorTitle by remember { mutableStateOf("") }
    var editorText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<VaultItem?>(null) }
    var exporting by remember { mutableStateOf<VaultItem?>(null) }
    var mediaPreview by remember { mutableStateOf<File?>(null) }
    var previewKind by remember { mutableStateOf<VaultKind?>(null) }

    val biometricAvailable = remember(context, configured) {
        configured && runCatching {
            context.getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }

    fun refreshItems() {
        scope.launch {
            busy = true
            try {
                items = withContext(Dispatchers.IO) { vault.list() }
                notice = null
            } catch (e: Exception) {
                notice = e.message ?: "Ошибка чтения сейфа"
            } finally {
                busy = false
            }
        }
    }

    DisposableEffect(activity, owner) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                unlocked = false
                pin = ""
                firstPin = null
                mediaPreview?.delete()
                mediaPreview = null
                previewKind = null
                items = emptyList()
                editingKind = null
                editing = null
                editorText = ""
                deleting = null
                exporting = null
                currentKind = null
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            mediaPreview?.delete()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { uris.forEach { vault.importMedia(it, VaultKind.PHOTO) } }
                if (unlocked) refreshItems()
                notice = "Фото добавлены в сейф. Исходные файлы сохранены."
            } catch (e: Exception) {
                notice = e.message ?: "Не удалось добавить фото"
            } finally { busy = false }
        }
    }
    val videos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { uris.forEach { vault.importMedia(it, VaultKind.VIDEO) } }
                if (unlocked) refreshItems()
                notice = "Видео добавлены в сейф. Исходные файлы сохранены."
            } catch (e: Exception) {
                notice = e.message ?: "Не удалось добавить видео"
            } finally { busy = false }
        }
    }
    val createExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val item = exporting
        exporting = null
        if (uri != null && item != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { vault.export(item, uri) }
                notice = "Экспортировано. Вне сейфа файл больше не зашифрован."
            } catch (e: Exception) {
                notice = e.message ?: "Не удалось экспортировать"
            } finally { busy = false }
        }
    }

    fun startEditor(kind: VaultKind, item: VaultItem? = null) {
        editingKind = kind
        editing = item
        editorTitle = item?.title.orEmpty()
        editorText = ""
        if (item != null) scope.launch {
            try {
                editorText = withContext(Dispatchers.IO) { vault.readText(item) }
            } catch (e: Exception) { notice = e.message ?: "Не удалось открыть запись" }
        }
    }

    fun openItem(item: VaultItem) {
        if (item.kind == VaultKind.LINK || item.kind == VaultKind.NOTE) {
            startEditor(item.kind, item)
        } else {
            scope.launch {
                busy = true
                try {
                    val file = withContext(Dispatchers.IO) { vault.preview(item) }
                    mediaPreview?.delete()
                    mediaPreview = file
                    previewKind = item.kind
                } catch (e: Exception) {
                    notice = e.message ?: "Не удалось открыть защищённый файл"
                } finally { busy = false }
            }
        }
    }

    fun goBack() {
        when {
            mediaPreview != null -> {
                mediaPreview?.delete()
                mediaPreview = null
                previewKind = null
            }
            currentKind != null && unlocked -> currentKind = null
            else -> navigator.back()
        }
    }
    BackHandler(enabled = mediaPreview != null || (currentKind != null && unlocked)) { goBack() }

    if (mediaPreview != null) {
        val file = mediaPreview!!
        DisposableEffect(file) { onDispose { file.delete() } }
        Column(Modifier.fillMaxSize().background(AppColors.Background)) {
            ScreenTopBar(title = "Личный сейф", onBack = { goBack() })
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (previewKind == VaultKind.PHOTO) {
                    AsyncImage(
                        model = file,
                        contentDescription = "Защищённое фото",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoPath(file.absolutePath)
                                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                setOnPreparedListener { it.isLooping = false; start() }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(AppColors.Background)) {
        ScreenTopBar(
            title = if (unlocked && currentKind != null) vaultKindTitle(currentKind!!) else "Личный сейф",
            onBack = { goBack() },
            actions = {
                if (unlocked) {
                    TextButton(onClick = {
                        unlocked = false
                        items = emptyList()
                        currentKind = null
                    }) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Закрыть")
                    }
                }
            },
        )
        if (!unlocked) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(17.dp),
            ) {
                Surface(shape = RoundedCornerShape(24.dp), color = AppColors.AccentContainer) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = AppColors.Accent,
                        modifier = Modifier.padding(26.dp).size(52.dp),
                    )
                }
                Text(
                    if (!configured) "Создать защищённый сейф" else "Сейф закрыт",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (!configured) "Фото, видео, ссылки и заметки будут храниться зашифрованными на этом устройстве."
                    else "Откройте сейф с помощью PIN или защищённой биометрии.",
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                    label = { Text(if (!configured && firstPin != null) "Повторите PIN" else "PIN · 4 цифры") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                )
                Button(
                    onClick = {
                        if (pin.length != 4) return@Button
                        val submittedPin = pin
                        if (!configured) {
                            val expected = firstPin
                            if (expected == null) {
                                firstPin = pin
                                pin = ""
                                notice = "Введите те же четыре цифры ещё раз."
                            } else if (expected != pin) {
                                firstPin = null
                                pin = ""
                                notice = "PIN не совпал. Придумайте PIN заново."
                            } else {
                                busy = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { vault.setupPin(submittedPin) }
                                        configured = true
                                        unlocked = true
                                        pin = ""
                                        firstPin = null
                                        refreshItems()
                                    } catch (e: Exception) {
                                        notice = e.message ?: "Не удалось создать сейф"
                                    } finally { busy = false }
                                }
                            }
                        } else {
                            busy = true
                            scope.launch {
                                try {
                                    val valid = withContext(Dispatchers.IO) { vault.checkPin(submittedPin) }
                                    if (valid) {
                                        unlocked = true
                                        pin = ""
                                        refreshItems()
                                    } else notice = "Неправильный PIN."
                                } catch (e: Exception) {
                                    notice = e.message ?: "Не удалось открыть сейф"
                                } finally { busy = false }
                            }
                        }
                    },
                    enabled = pin.length == 4 && !busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(if (!configured) (if (firstPin == null) "Продолжить" else "Создать сейф") else "Открыть") }

                if (biometricAvailable && configured && activity != null) {
                    TextButton(
                        onClick = {
                            val prompt = BiometricPrompt.Builder(activity)
                                .setTitle("Открыть личный сейф")
                                .setSubtitle("Подтвердите вход защищённой биометрией Android")
                                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                                .setNegativeButton("Ввести PIN", activity.mainExecutor) { _, _ -> }
                                .build()
                            prompt.authenticate(
                                CancellationSignal(),
                                activity.mainExecutor,
                                object : BiometricPrompt.AuthenticationCallback() {
                                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                            unlocked = true
                                            refreshItems()
                                        }
                                    }
                                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                        // Dismiss and PIN fallback are expected: don't turn them into errors.
                                    }
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Открыть биометрией")
                    }
                }
                Text(
                    "Данные привязаны к ключу этого устройства. При удалении приложения или потере ключа сейф не восстановить. Сохраняйте нужные копии отдельно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
                if (notice != null) Text(notice.orEmpty(), color = AppColors.Accent)
            }
        } else if (currentKind == null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.Card),
                ) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LockOpen, contentDescription = null, tint = AppColors.Accent,
                            modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Защищённое пространство", style = MaterialTheme.typography.titleMedium)
                            Text("Только на этом телефоне · шифрование AES-256-GCM",
                                color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VaultTile(VaultKind.PHOTO, items.count { it.kind == VaultKind.PHOTO },
                        Modifier.weight(1f)) { currentKind = VaultKind.PHOTO }
                    VaultTile(VaultKind.VIDEO, items.count { it.kind == VaultKind.VIDEO },
                        Modifier.weight(1f)) { currentKind = VaultKind.VIDEO }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VaultTile(VaultKind.LINK, items.count { it.kind == VaultKind.LINK },
                        Modifier.weight(1f)) { currentKind = VaultKind.LINK }
                    VaultTile(VaultKind.NOTE, items.count { it.kind == VaultKind.NOTE },
                        Modifier.weight(1f)) { currentKind = VaultKind.NOTE }
                }
                Text(
                    "Файлы из памяти телефона копируются в сейф в зашифрованном виде. Оригиналы остаются на месте.",
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (notice != null) Text(notice.orEmpty(), color = AppColors.Accent)
            }
        } else {
            val kind = currentKind!!
            val entries = items.filter { it.kind == kind }
            Column(
                Modifier.fillMaxSize().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${entries.size} элементов", modifier = Modifier.weight(1f),
                        color = AppColors.TextSecondary)
                    Button(
                        onClick = {
                            when (kind) {
                                VaultKind.PHOTO -> photos.launch("image/*")
                                VaultKind.VIDEO -> videos.launch("video/*")
                                VaultKind.LINK, VaultKind.NOTE -> startEditor(kind)
                            }
                        },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить")
                    }
                }
                if (notice != null) Text(notice.orEmpty(), color = AppColors.Accent)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (entries.isEmpty()) {
                        Text("Пока пусто. Добавьте первый элемент.", color = AppColors.TextSecondary)
                    }
                    entries.forEach { item ->
                        var menu by remember(item.id) { mutableStateOf(false) }
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = AppColors.Card),
                            onClick = { openItem(item) },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(vaultKindIcon(kind), contentDescription = null,
                                    tint = vaultKindColor(kind), modifier = Modifier.size(44.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge)
                                    Text(formatSize(item.bytes), color = AppColors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Box {
                                    IconButton(onClick = { menu = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "Действия")
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Открыть") },
                                            onClick = { menu = false; openItem(item) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Экспортировать незашифрованную копию") },
                                            onClick = {
                                                menu = false
                                                exporting = item
                                                createExport.launch(item.title)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Удалить из сейфа", color = AppColors.Danger) },
                                            onClick = { menu = false; deleting = item },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val kindToEdit = editingKind
    if (kindToEdit != null) {
        AlertDialog(
            onDismissRequest = { editingKind = null; editing = null; editorText = "" },
            title = { Text(if (editing == null) "Новая запись" else "Редактировать") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editorTitle, onValueChange = { editorTitle = it.take(160) },
                        label = { Text("Название") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = editorText,
                        onValueChange = { if (it.length <= 2 * 1024 * 1024) editorText = it },
                        label = { Text(if (kindToEdit == VaultKind.LINK) "Ссылка" else "Текст заметки") },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = editing?.id
                        val titleToSave = editorTitle
                        val textToSave = editorText
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    vault.saveText(kindToEdit, titleToSave, textToSave, id)
                                }
                                editingKind = null
                                editing = null
                                editorText = ""
                                refreshItems()
                            } catch (e: Exception) {
                                notice = e.message ?: "Ошибка сохранения"
                            } finally { busy = false }
                        }
                    },
                    enabled = editorTitle.isNotBlank() && !busy,
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { editingKind = null; editing = null; editorText = "" }) {
                    Text("Отмена")
                }
            },
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить из сейфа?") },
            text = {
                Text("«" + item.title + "» будет удалён из закрытого хранилища без корзины. " +
                    "Исходный файл, если он остался вне сейфа, не затрагивается.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        scope.launch {
                            busy = true
                            try {
                                withContext(Dispatchers.IO) { vault.delete(item) }
                                refreshItems()
                            } catch (e: Exception) {
                                notice = e.message ?: "Не удалось удалить"
                            } finally { busy = false }
                        }
                    },
                ) { Text("Удалить", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun VaultTile(kind: VaultKind, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(156.dp),
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = AppColors.Card,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                vaultKindIcon(kind),
                contentDescription = null,
                tint = vaultKindColor(kind),
                modifier = Modifier.size(40.dp),
            )
            Column {
                Text(vaultKindTitle(kind), style = MaterialTheme.typography.titleMedium)
                Text("${count} элементов", color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun vaultKindTitle(kind: VaultKind): String = when (kind) {
    VaultKind.PHOTO -> "Фото"
    VaultKind.VIDEO -> "Видео"
    VaultKind.LINK -> "Ссылки"
    VaultKind.NOTE -> "Заметки"
}

private fun vaultKindIcon(kind: VaultKind): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    VaultKind.PHOTO -> Icons.Filled.PhotoLibrary
    VaultKind.VIDEO -> Icons.Filled.VideoLibrary
    VaultKind.LINK -> Icons.Filled.Link
    VaultKind.NOTE -> Icons.Filled.Note
}

private fun vaultKindColor(kind: VaultKind): Color = when (kind) {
    VaultKind.PHOTO -> Color(0xFFC19AFF)
    VaultKind.VIDEO -> Color(0xFFFF8D9A)
    VaultKind.LINK -> Color(0xFF72CEC3)
    VaultKind.NOTE -> Color(0xFFEFC27A)
}
