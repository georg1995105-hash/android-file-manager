package io.personal.filemanager.ui.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.personal.filemanager.core.SectionId
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.components.EmptyState
import io.personal.filemanager.ui.components.ScreenTopBar

/**
 * Разделы этапа 5 (Заметки, Ссылки, Избранное). Честное сообщение вместо фиктивного интерфейса:
 * плитку/вкладку можно скрыть в настройках.
 */
@Composable
fun PlannedSectionScreen(section: SectionId) {
    val navigator = LocalNavigator.current
    val text = when (section) {
        SectionId.NOTES -> "Локальные заметки с автосохранением, экспортом и резервной копией появятся на этапе 5."
        SectionId.LINKS -> "Список ссылок с поиском, копированием и экспортом появится на этапе 5."
        SectionId.FAVORITES -> "Избранные файлы и папки появятся на этапе 5."
        else -> "Раздел в разработке."
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title = section.title, onBack = { navigator.back() })
        EmptyState(
            icon = Icons.Filled.Construction,
            title = "Раздел пока не реализован",
            text = "$text\n\nЕсли он мешает, скройте его: «Настроить» на главной или «Настроить нижнюю панель» в меню.",
        )
    }
}
