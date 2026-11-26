package io.personal.filemanager.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.BuildConfig
import io.personal.filemanager.data.settings.UiConfig
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.Routes
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.components.tvRemoteFocus
import io.personal.filemanager.ui.theme.AppColors
import kotlinx.coroutines.launch

/** One settings screen, shared by the main tab and the storage shortcut. */
@Composable
fun AppSettingsScreen(showBack: Boolean = true) {
    val navigator = LocalNavigator.current
    val settings = appContainer().settings
    val config by settings.uiConfig.collectAsStateWithLifecycle(initialValue = UiConfig.defaults())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Настройки", onBack = if (showBack) ({ navigator.back() }) else null)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsGroup("Приложение") {
                SettingsRow("Версия и обновления", Icons.Filled.SystemUpdate, BuildConfig.VERSION_NAME) { navigator.open(Routes.UPDATES) }
                HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                SettingsRow("Резервные копии", Icons.Filled.Save) { navigator.open(Routes.BACKUP) }
            }
            SettingsGroup("Хранилище") {
                SettingsRow("Личное облако", Icons.Filled.Cloud, "Google Диск") { navigator.open(Routes.CLOUD) }
                HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                SettingsRow("Анализ памяти", Icons.Filled.Storage) { navigator.open(Routes.ANALYZER) }
                HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                SettingsRow("Корзина", Icons.Filled.DeleteOutline) { navigator.open(Routes.TRASH) }
                SettingsToggle("Использовать корзину", "При выключении — удаление без восстановления.", config.trashEnabled) { value ->
                    scope.launch { settings.updateUiConfig { it.copy(trashEnabled = value) } }
                }
                SettingsToggle("Корзина в меню", "Сохранённые файлы не удаляются.", config.showTrashShortcut) { value ->
                    scope.launch { settings.updateUiConfig { it.copy(showTrashShortcut = value) } }
                }
            }
            SettingsGroup("Личные данные") {
                SettingsRow("Личный сейф", Icons.Filled.Lock, "PIN · лицо или отпечаток") { navigator.open(Routes.VAULT) }
            }
            SettingsGroup("Внешний вид") {
                SettingsRow("Главная и размер плиток", Icons.Filled.GridView) { navigator.open(Routes.SETTINGS_TILES) }
                HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                SettingsRow("Нижняя панель и разделы", Icons.Filled.ViewModule) { navigator.open(Routes.SETTINGS_TABS) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleSmall,
            color = AppColors.TextSecondary)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AppColors.Card), content = content)
    }
}

@Composable
private fun SettingsRow(title: String, icon: ImageVector, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().tvRemoteFocus(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .heightIn(min = 54.dp).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(23.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AppColors.TextSecondary)
    }
}

@Composable
private fun SettingsToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.tvRemoteFocus())
    }
}
