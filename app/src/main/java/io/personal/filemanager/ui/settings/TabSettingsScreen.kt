package io.personal.filemanager.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.AppContainer
import io.personal.filemanager.core.SectionId
import io.personal.filemanager.data.settings.MAX_TABS
import io.personal.filemanager.data.settings.TabConfig
import io.personal.filemanager.data.settings.UiConfig
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.components.IconColorPickerSheet
import io.personal.filemanager.ui.components.LoadingBox
import io.personal.filemanager.ui.components.ReorderableColumn
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.components.SectionIcon
import io.personal.filemanager.ui.components.card
import io.personal.filemanager.ui.components.moved
import io.personal.filemanager.ui.theme.AccentColors
import io.personal.filemanager.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Черновик нижней панели: применяется только кнопкой «Сохранить» (как в макете). Переживает поворот. */
class TabSettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val _draft = MutableStateFlow<List<TabConfig>?>(null)
    val draft: StateFlow<List<TabConfig>?> = _draft.asStateFlow()

    init {
        viewModelScope.launch { _draft.value = container.settings.uiConfig.first().tabs }
    }

    private fun edit(transform: (List<TabConfig>) -> List<TabConfig>) {
        _draft.value?.let { _draft.value = transform(it) }
    }

    fun move(from: Int, to: Int) = edit { it.moved(from, to) }
    fun remove(id: String) = edit { list -> list.filterNot { it.id == id } }
    fun add(id: SectionId) = edit { list ->
        if (list.size >= MAX_TABS || list.any { it.id == id.name }) list else list + UiConfig.defaultTab(id)
    }
    fun restyle(id: String, icon: String, color: String) = edit { list ->
        list.map { if (it.id == id) it.copy(icon = icon, color = color) else it }
    }
    fun reset() = edit { UiConfig.defaultTabsConfig() }

    fun save(onSaved: () -> Unit) {
        val tabs = _draft.value ?: return
        if (tabs.isEmpty()) return
        viewModelScope.launch {
            container.settings.updateUiConfig { it.copy(tabs = tabs) }
            onSaved()
        }
    }
}

@Composable
fun TabSettingsScreen() {
    val navigator = LocalNavigator.current
    val vm = appViewModel { TabSettingsViewModel(it) }
    val draft by vm.draft.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Настроить панель", onBack = { navigator.back() })
        val tabs = draft
        if (tabs == null) {
            LoadingBox()
            return@Column
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            Text(
                "Выберите вкладки для нижней панели. Можно менять иконки, порядок (перетащите за ⠿) " +
                    "и добавлять разделы. Остальные разделы доступны с главного экрана.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Row(
                Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Выбранные вкладки (${tabs.size}/$MAX_TABS)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Сбросить",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, AppColors.CardBorder, RoundedCornerShape(12.dp))
                        .background(AppColors.Card)
                        .clickable { vm.reset() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Column(Modifier.padding(horizontal = 12.dp).card(RoundedCornerShape(20.dp))) {
                if (tabs.isEmpty()) {
                    Text(
                        "Нужна хотя бы одна вкладка — добавьте её из списка ниже.",
                        color = AppColors.Danger,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                ReorderableColumn(items = tabs, keyOf = { it.id }, onMove = vm::move) { tab, index, handle, _ ->
                    val id = SectionId.fromKey(tab.id) ?: return@ReorderableColumn
                    Column {
                        if (index > 0) HorizontalDivider(color = AppColors.Divider, modifier = Modifier.padding(start = 56.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(AppColors.Card)
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(handle.size(40.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.DragIndicator, contentDescription = "Перетащить «${id.title}»", tint = AppColors.TextSecondary)
                            }
                            SectionIcon(tab.icon, tab.color, 34.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(id.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            CircleAction(onClick = { editing = tab.id }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Изменить значок «${id.title}»", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { vm.remove(tab.id) }) {
                                Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "Убрать «${id.title}»")
                            }
                        }
                    }
                }
            }

            val available = SectionId.entries.filter { s -> tabs.none { it.id == s.name } }
            if (available.isNotEmpty()) {
                Text(
                    "Доступные вкладки",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
                )
                val full = tabs.size >= MAX_TABS
                Column(Modifier.padding(horizontal = 12.dp).card(RoundedCornerShape(20.dp))) {
                    available.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = AppColors.Divider, modifier = Modifier.padding(start = 56.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionIcon(s.defaultIcon, s.defaultColor, 30.dp)
                            Spacer(Modifier.width(16.dp))
                            Text(s.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.add(s) }, enabled = !full) {
                                Icon(Icons.Filled.AddCircleOutline, contentDescription = "Добавить «${s.title}»")
                            }
                        }
                    }
                }
                if (full) {
                    Text(
                        "Максимум $MAX_TABS вкладок — уберите одну, чтобы добавить другую.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // Закреплённый низ: предпросмотр и кнопки — в зоне большого пальца.
        Column(
            Modifier
                .fillMaxWidth()
                .background(AppColors.Surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Text("Предпросмотр панели", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            TabBarPreview(tabs)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { navigator.back() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Card, contentColor = AppColors.TextPrimary),
                ) { Text("Отмена") }
                Button(
                    onClick = { vm.save { navigator.back() } },
                    enabled = tabs.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Сохранить") }
            }
        }
    }

    val editingTab = draft?.firstOrNull { it.id == editing }
    if (editingTab != null) {
        IconColorPickerSheet(
            title = SectionId.fromKey(editingTab.id)?.title.orEmpty(),
            iconKey = editingTab.icon,
            colorKey = editingTab.color,
            onChange = { icon, color -> vm.restyle(editingTab.id, icon, color) },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun CircleAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(AppColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun TabBarPreview(tabs: List<TabConfig>) {
    Row(
        Modifier
            .fillMaxWidth()
            .card(RoundedCornerShape(18.dp))
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        if (tabs.isEmpty()) {
            Text("Нет вкладок", color = AppColors.Danger, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        tabs.forEach { tab ->
            val id = SectionId.fromKey(tab.id) ?: return@forEach
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                SectionIcon(tab.icon, tab.color, 26.dp)
                Spacer(Modifier.height(4.dp))
                Text(
                    id.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentColors.of("white"),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
