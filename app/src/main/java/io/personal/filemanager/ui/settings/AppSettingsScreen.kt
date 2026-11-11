package io.personal.filemanager.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.data.settings.UiConfig
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.Routes
import io.personal.filemanager.ui.appContainer
import io.personal.filemanager.ui.components.ScreenTopBar
import kotlinx.coroutines.launch

/** Общие настройки: единая точка доступа к корзине и параметрам интерфейса. */
@Composable
fun AppSettingsScreen() {
    val navigator = LocalNavigator.current
    val settings = appContainer().settings
    val config by settings.uiConfig.collectAsStateWithLifecycle(initialValue = UiConfig.defaults())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = "Настройки", onBack = { navigator.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Удаление и корзина", style = MaterialTheme.typography.titleMedium)
            SettingsToggle(
                title = "Использовать корзину",
                detail = "Если выключено, удаление будет безвозвратным после одного подтверждения. Уже сохранённые в корзине файлы останутся.",
                checked = config.trashEnabled,
                onChange = { value ->
                    scope.launch { settings.updateUiConfig { it.copy(trashEnabled = value) } }
                },
            )
            SettingsToggle(
                title = "Показывать корзину в меню",
                detail = "Убирает только пункт главного меню, сама корзина остаётся доступна здесь.",
                checked = config.showTrashShortcut,
                onChange = { value ->
                    scope.launch { settings.updateUiConfig { it.copy(showTrashShortcut = value) } }
                },
            )
            TextButton(
                onClick = { navigator.open(Routes.TRASH) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Открыть корзину") }
            HorizontalDivider()
            Text("Внешний вид", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { navigator.open(Routes.SETTINGS_TILES) }, modifier = Modifier.fillMaxWidth()) {
                Text("Главная и размер плиток")
            }
            TextButton(onClick = { navigator.open(Routes.SETTINGS_TABS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Нижняя панель и разделы")
            }
            HorizontalDivider()
            Text("Инструменты", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { navigator.open(Routes.ANALYZER) }, modifier = Modifier.fillMaxWidth()) {
                Text("Анализ памяти")
            }
            TextButton(onClick = { navigator.open(Routes.BACKUP) }, modifier = Modifier.fillMaxWidth()) {
                Text("Резервные копии")
            }
            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun SettingsToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.padding(6.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
