package io.personal.filemanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.personal.filemanager.FileManagerApp
import io.personal.filemanager.ui.FileActions
import io.personal.filemanager.ui.icons.IconCatalog
import io.personal.filemanager.ui.theme.AccentColors
import io.personal.filemanager.ui.theme.AppColors

/** Верхняя строка экрана: «назад», заголовок, необязательные действия справа. */
@Composable
fun ScreenTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/** Цветной значок раздела из встроенного набора. */
@Composable
fun SectionIcon(iconKey: String, colorKey: String, size: Dp, modifier: Modifier = Modifier) {
    Icon(
        imageVector = IconCatalog.of(iconKey),
        contentDescription = null,
        tint = AccentColors.of(colorKey),
        modifier = modifier.size(size),
    )
}

/** Карточка в стиле макета: тёмная, скруглённая, с тонкой рамкой. */
fun Modifier.card(shape: RoundedCornerShape = RoundedCornerShape(18.dp)): Modifier =
    this
        .background(AppColors.Card, shape)
        .border(1.dp, AppColors.CardBorder, shape)

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Пояснение и кнопка выдачи доступа ко всем файлам. Реальные файлы не подменяются примерами. */
@Composable
fun AllFilesAccessRequired(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    EmptyState(
        icon = Icons.Filled.FolderOff,
        title = "Нужен доступ к файлам",
        text = "Чтобы показывать документы, загрузки, архивы и папки, разрешите приложению " +
            "«Доступ ко всем файлам». Это не root: Android по-прежнему закрывает папки " +
            "Android/data и Android/obb. Данные никуда не отправляются — у приложения нет доступа в интернет.",
        modifier = modifier,
        action = {
            Button(onClick = { FileActions.openAllFilesAccessSettings(context) }) {
                Text("Открыть настройки доступа")
            }
        },
    )
}

/** Есть ли доступ ко всем файлам; перепроверяется при каждом возврате в приложение. */
@Composable
fun rememberAllFilesAccess(): Boolean {
    val context = LocalContext.current
    val storage = remember { (context.applicationContext as FileManagerApp).container.storage }
    var granted by remember { mutableStateOf(storage.hasAllFilesAccess()) }
    LifecycleResumeEffect(Unit) {
        granted = storage.hasAllFilesAccess()
        onPauseOrDispose { }
    }
    return granted
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = AppColors.OnAccentContainer, containerColor: Color = AppColors.AccentContainer) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(containerColor, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
