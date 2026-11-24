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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import io.personal.filemanager.core.FileKind
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import io.personal.filemanager.ui.personal.SiteIcon
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
            Box(Modifier.padding(8.dp).size(52.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Color(0xFF276BDB),Color(0xFF123568)))), contentAlignment = Alignment.Center) { Icon(Icons.Filled.SwapHoriz, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
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
    val fg = if (selected || focused) Color.White else AppColors.TextSecondary
    Box(modifier.height(if (side) 56.dp else 66.dp).testTag("hub-tab-${tab.name}")
        .onFocusChanged { focused = it.isFocused }.clip(shape)
        .background(if (selected || focused) Color(0xFF1B4D92) else Color.Transparent)
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
    val storagePath = container.storage.rootPath
    val storageStats by produceState<Pair<Long,Long>?>(null, storagePath) {
        value = withContext(Dispatchers.IO) {
            runCatching { android.os.StatFs(storagePath).let { it.totalBytes to it.availableBytes } }.getOrNull()
        }
    }
    val receivedSize = remember(received) { received.sumOf { it.size } }
    val recentMedia = remember(received) { received.filter { it.kind == FileKind.IMAGE || it.kind == FileKind.VIDEO } }
    val subtitle = if (received.isEmpty()) "Файлы с ваших устройств" else "${received.size} · ${formatSize(receivedSize)}"
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF101827),Color(0xFF152237))))
        .statusBarsPadding().verticalScroll(rememberScrollState()).padding(if (wide) 24.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Хранилище", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { nav.openSearch() }) { Icon(Icons.Filled.Search, "Поиск локальных файлов") }
        }
        if (tv) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvTile("Полученные файлы", subtitle, Icons.Filled.Folder, Modifier.weight(1f), openReceived)
                TvTile("Отправить с телефона", "QR-код · Локальная сеть", Icons.Filled.Smartphone, Modifier.weight(1f)) { nav.openSection(SectionId.TRANSFER) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvTile("Личное облако", "Google Диск", Icons.Filled.Cloud, Modifier.weight(1f)) { nav.open(Routes.CLOUD) }
                TvTile("Файлы устройства", "Фото, видео, APK", Icons.Filled.Storage, Modifier.weight(1f)) { nav.open(Routes.LOCAL_FILES) }
            }
        } else {
            val memory: @Composable (Modifier) -> Unit = { m ->
                val stats = storageStats
                val used = stats?.let { (it.first - it.second).coerceAtLeast(0) }
                val progress = stats?.let { if (it.first > 0) ((it.first-it.second).toDouble()/it.first).toFloat().coerceIn(0f,1f) else 0f } ?: 0f
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(18.dp)
                Row(m.onFocusChanged { focused = it.isFocused }.clip(shape).background(Panel)
                    .border(if (focused) 2.dp else 1.dp, if (focused) Blue else AppColors.CardBorder,shape)
                    .clickable { nav.open(Routes.LOCAL_FILES) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Filled.Storage,null,tint=Blue,modifier=Modifier.size(34.dp))
                    Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Внутренняя память",style=MaterialTheme.typography.titleMedium)
                        Text(if (stats != null && used != null) "${formatSize(used)} из ${formatSize(stats.first)}" else "Файлы устройства",
                            color=AppColors.TextSecondary,style=MaterialTheme.typography.bodySmall)
                        if(stats != null) LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth(),color=Color(0xFF32B9B3),trackColor=AppColors.CardBorder)
                    }
                    Icon(Icons.Filled.ChevronRight,null,tint=AppColors.TextSecondary)
                }
            }
            memory(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF368BFF),Color(0xFF1760D8))))
                .clickable(role=Role.Button) { nav.openSection(SectionId.TRANSFER) },contentAlignment=Alignment.Center) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Send,null,tint=Color.White);Text("Отправить файл",color=Color.White,fontWeight=FontWeight.SemiBold)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        HubCard("Полученные файлы",subtitle,Icons.Filled.Folder,Modifier.fillMaxWidth(),openReceived)
                        HubCard("Личное облако","Google Диск",Icons.Filled.Cloud,Modifier.fillMaxWidth()) { nav.open(Routes.CLOUD) }
                    }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Panel).border(1.dp,AppColors.CardBorder,RoundedCornerShape(18.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Ссылки",style=MaterialTheme.typography.titleMedium)
                        val shortcuts=links.orEmpty().take(4)
                        if(shortcuts.isEmpty()) Text("Добавьте любимые сайты",color=AppColors.TextSecondary)
                        shortcuts.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                pair.forEach { link ->
                                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(AppColors.Surface).clickable { nav.openSection(SectionId.LINKS) }.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                        SiteIcon(link.url,true,true)
                                        Text(link.title.ifBlank { link.url },maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if(pair.size==1) Spacer(Modifier.weight(1f))
                            }
                        }
                        TextButton(onClick={nav.openSection(SectionId.LINKS)}) { Text("Все ссылки") }
                    }
                }
            } else {
                HubCard("Полученные файлы",subtitle,Icons.Filled.Folder,Modifier.fillMaxWidth(),compact=true,onClick=openReceived)
                HubCard("Личное облако","Google Диск",Icons.Filled.Cloud,Modifier.fillMaxWidth(),compact=true) { nav.open(Routes.CLOUD) }
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Недавние файлы",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
            TextButton(onClick=openReceived) { Text("Все") }
        }
        if (received.isEmpty()) Text("Полученные файлы появятся здесь",color=AppColors.TextSecondary,style=MaterialTheme.typography.bodyMedium)
        if(recentMedia.isNotEmpty()) {
            LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                items(recentMedia.take(10),key={it.path}) { entry ->
                    var focused by remember { mutableStateOf(false) }
                    val shape=RoundedCornerShape(14.dp)
                    Column(Modifier.width(if(tv) 150.dp else 120.dp).onFocusChanged { focused=it.isFocused }.clip(shape)
                        .background(Panel).border(if(focused) 2.dp else 1.dp,if(focused) Blue else AppColors.CardBorder,shape)
                        .clickable { nav.openFile(entry,recentMedia) }.padding(8.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(9.dp)).background(AppColors.Surface),contentAlignment=Alignment.Center) {
                            AsyncImage(model=entry.file,contentDescription=entry.name,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
                            if(entry.kind==FileKind.VIDEO) Icon(Icons.Filled.PlayCircle,null,tint=Color.White,modifier=Modifier.size(36.dp))
                        }
                        Text(entry.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        received.filterNot { it.kind==FileKind.IMAGE || it.kind==FileKind.VIDEO }.take(if(tv) 4 else 5).forEach { entry ->
            HubCard(entry.name,if(entry.isDirectory) "Папка" else formatSize(entry.size),if(entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,Modifier.fillMaxWidth(),compact=true) { nav.openFile(entry,received) }
        }
    }
}

@Composable
private fun TvTile(title:String,subtitle:String,icon:ImageVector,modifier:Modifier,onClick:()->Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape=RoundedCornerShape(20.dp)
    Box(modifier.heightIn(min=142.dp).onFocusChanged { focused=it.isFocused }.clip(shape)
        .background(Brush.verticalGradient(if(focused) listOf(Color(0xFF28476E),Color(0xFF182B44)) else listOf(Color(0xFF27364B),Color(0xFF1B283B))))
        .border(if(focused) 3.dp else 1.dp,if(focused) Color(0xFF69B1FF) else AppColors.CardBorder,shape)
        .clickable(role=Role.Button,onClick=onClick).padding(18.dp)) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()) {
            Icon(icon,null,tint=if(focused) Color.White else Color(0xFFA9C9F3),modifier=Modifier.size(34.dp))
            Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2)
            Text(subtitle,color=AppColors.TextSecondary,style=MaterialTheme.typography.bodySmall,maxLines=2)
        }
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
        HubCard("Хранилище и устройства", "Подключение и привязка", Icons.Filled.Devices, Modifier.fillMaxWidth(), compact = true) { nav.open(Routes.CLOUD) }
        HubCard("Резервные копии", "Сохранить ссылки и настройки", Icons.Filled.Save, Modifier.fillMaxWidth(), compact = true) { nav.open(Routes.BACKUP) }
        HubCard("Личный сейф", "Защищённые локальные данные", Icons.Filled.Lock, Modifier.fillMaxWidth(), compact = true) { nav.open(Routes.VAULT) }
        HubCard("Дополнительные настройки", "Корзина и локальные файлы", Icons.Filled.Settings, Modifier.fillMaxWidth(), compact = true) { nav.open(Routes.SETTINGS) }
    }
}
