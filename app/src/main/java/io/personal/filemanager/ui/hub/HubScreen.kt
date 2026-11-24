package io.personal.filemanager.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.BuildConfig
import io.personal.filemanager.core.SectionId
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.ui.*
import io.personal.filemanager.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val Blue = Color(0xFF4D9DFF)
private val Panel = Color(0xFF242D3D)

internal enum class HubTab(val label: String, val icon: ImageVector, val route: String) {
    STORAGE("Хранилище", Icons.Filled.Storage, Routes.HOME),
    TRANSFER("Передача", Icons.Filled.SwapHoriz, Routes.section(SectionId.TRANSFER)),
    LINKS("Ссылки", Icons.Filled.Link, Routes.section(SectionId.LINKS)),
    SETTINGS("Настройки", Icons.Filled.Settings, Routes.HUB_SETTINGS),
}

@Composable
internal fun HubNavigation(current: HubTab?, side: Boolean, onSelect: (HubTab) -> Unit) {
    if (side) {
        Column(Modifier.width(184.dp).fillMaxHeight().background(AppColors.Surface)
            .statusBarsPadding().navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.SwapHoriz, null, tint = Blue, modifier = Modifier.padding(12.dp).size(32.dp))
            Text("Мои файлы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(12.dp))
            HubTab.entries.forEach { tab -> HubNavItem(tab, current == tab, true, Modifier.fillMaxWidth(), onSelect) }
        }
    } else {
        Row(Modifier.fillMaxWidth().background(AppColors.Surface).navigationBarsPadding().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            HubTab.entries.forEach { tab -> HubNavItem(tab, current == tab, false, Modifier.weight(1f), onSelect) }
        }
    }
}

@Composable
private fun HubNavItem(tab: HubTab, selected: Boolean, side: Boolean, modifier: Modifier, onSelect: (HubTab) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val fg = if (selected || focused) Blue else AppColors.TextSecondary
    Box(modifier.height(if (side) 56.dp else 66.dp).testTag("hub-tab-${tab.name}")
        .onFocusChanged { focused = it.isFocused }.clip(shape)
        .background(if (selected || focused) Blue.copy(alpha = .14f) else Color.Transparent)
        .border(if (focused) 2.dp else 0.dp, if (focused) Blue else Color.Transparent, shape)
        .clickable(role = Role.Tab) { onSelect(tab) }.semantics { this.selected = selected },
        contentAlignment = if (side) Alignment.CenterStart else Alignment.Center) {
        if (side) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(tab.icon, null, tint = fg)
            Text(tab.label, color = fg, maxLines = 1, style = MaterialTheme.typography.labelLarge)
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(tab.icon, null, tint = fg)
            Text(tab.label, color = fg, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun HubHome() {
    val container = appContainer()
    val nav = LocalNavigator.current
    val tv = isTelevisionUi()
    val wide = tv || LocalConfiguration.current.screenWidthDp >= 700
    val transfer by container.transfer.state.collectAsStateWithLifecycle()
    val links by container.personal.links.collectAsStateWithLifecycle(initialValue = null)
    val received by produceState<List<FileEntry>>(emptyList(), transfer.progress, transfer.message) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                container.transfer.receivedDirectory.listFiles().orEmpty()
                    .filterNot { it.name.startsWith(".") }
                    .sortedByDescending { it.lastModified() }.take(50).map { FileEntry.from(it) }
            }.getOrDefault(emptyList())
        }
    }
    val openReceived: () -> Unit = {
        val dir = container.transfer.receivedDirectory
        if (dir.isDirectory || dir.mkdirs()) nav.open(Routes.browser(dir.absolutePath))
        else container.operations.notify("Не удалось открыть папку полученных файлов")
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(if (wide) 24.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Хранилище", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { nav.openSearch() }) { Icon(Icons.Filled.Search, "Поиск локальных файлов") }
        }
        Text("На этом устройстве", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        val receiveCard: @Composable (Modifier) -> Unit = { m ->
            HubCard("Полученные файлы", "Файлы, принятые по локальной сети", Icons.Filled.Folder, m, openReceived)
        }
        val sendCard: @Composable (Modifier) -> Unit = { m ->
            HubCard("Передача файлов", if (transfer.running) "Локальная передача включена" else "Отправить или принять файлы",
                Icons.Filled.SwapHoriz, m) { nav.openSection(SectionId.TRANSFER) }
        }
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                receiveCard(Modifier.weight(1f)); sendCard(Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HubCard("Ссылки", "${links?.size ?: 0} сохранённых", Icons.Filled.Link, Modifier.weight(1f)) { nav.openSection(SectionId.LINKS) }
                HubCard("Файлы устройства", "Папки, APK и документы", Icons.Filled.Storage, Modifier.weight(1f)) { nav.open(Routes.LOCAL_FILES) }
            }
        } else {
            receiveCard(Modifier.fillMaxWidth())
            Button(onClick = { nav.openSection(SectionId.TRANSFER) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color(0xFF07182C))) {
                Icon(Icons.Filled.SwapHoriz, null); Spacer(Modifier.width(10.dp)); Text("Передать файлы")
            }
        }
        Text("Недавние полученные", style = MaterialTheme.typography.titleMedium)
        if (received.isEmpty()) Text("Здесь появятся файлы после передачи.", color = AppColors.TextSecondary)
        received.take(if (wide) 8 else 5).forEach { entry ->
            HubCard(entry.name, if (entry.isDirectory) "Папка" else formatSize(entry.size),
                if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                Modifier.fillMaxWidth(), compact = true) { nav.openFile(entry) }
        }
        if (!wide) HubCard("Файлы устройства", "Локальная память и приложения", Icons.Filled.Storage, Modifier.fillMaxWidth(), compact = true) { nav.open(Routes.LOCAL_FILES) }
    }
}

@Composable
private fun HubCard(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) =
    HubCard(title, subtitle, icon, modifier, false, onClick)

@Composable
private fun HubCard(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, compact: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Row(modifier.onFocusChanged { focused = it.isFocused }.clip(shape)
        .background(if (focused) Blue.copy(alpha = .16f) else Panel)
        .border(if (focused) 2.dp else 1.dp, if (focused) Blue else AppColors.CardBorder, shape)
        .clickable(role = Role.Button, onClick = onClick).heightIn(min = if (compact) 64.dp else 104.dp)
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = Blue, modifier = Modifier.size(if (compact) 26.dp else 34.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = AppColors.TextSecondary)
    }
}

@Composable
internal fun HubSettings() {
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        HubCard("Версия и обновления", "Мои файлы ${BuildConfig.VERSION_NAME}", Icons.Filled.SystemUpdate, Modifier.fillMaxWidth(), compact = true) { nav.open(Routes.UPDATES) }
        HubCard("Резервные копии", "Сохранить ссылки и настройки", Icons.Filled.Save, Modifier.fillMaxWidth(), compact = true) { nav.open(Routes.BACKUP) }
        HubCard("Личный сейф", "Защищённые локальные данные", Icons.Filled.Lock, Modifier.fillMaxWidth(), compact = true) { nav.open(Routes.VAULT) }
        HubCard("Дополнительные настройки", "Корзина и локальные файлы", Icons.Filled.Settings, Modifier.fillMaxWidth(), compact = true) { nav.open(Routes.SETTINGS) }
    }
}
