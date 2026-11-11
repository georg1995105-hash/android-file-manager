package io.personal.filemanager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Спокойная графитовая палитра с бирюзовым акцентом. Цвета иконок разделов остаются настраиваемыми. */
object AppColors {
    val Background = Color(0xFF1A1E26)
    val Surface = Color(0xFF222833)
    val Card = Color(0xFF29313D)
    val CardBorder = Color(0xFF3A4554)
    val Accent = Color(0xFF31B8AD)
    val AccentContainer = Color(0xFF143835)
    val OnAccentContainer = Color(0xFFACE8DF)
    val TextPrimary = Color(0xFFF2F4F7)
    val TextSecondary = Color(0xFFB5BDCA)
    val Divider = Color(0xFF36404C)
    val Danger = Color(0xFFFF5A5F)
}

/** Встроенная палитра цветов значков (ключи — core/ColorKeys). */
object AccentColors {
    private val map = mapOf(
        "blue" to Color(0xFF2D8CFF),
        "blue_light" to Color(0xFF4D9DFF),
        "cyan" to Color(0xFF2EC5E8),
        "teal" to Color(0xFF1FC8A6),
        "green" to Color(0xFF35D25B),
        "lime" to Color(0xFF9BD43A),
        "orange" to Color(0xFFFFB31A),
        "red" to Color(0xFFFF3B4E),
        "pink" to Color(0xFFFF5FA2),
        "purple" to Color(0xFFB25CFF),
        "gray" to Color(0xFFC3C8D1),
        "white" to Color(0xFFF5F6F8),
    )

    val names = mapOf(
        "blue" to "Синий", "blue_light" to "Голубой", "cyan" to "Бирюзовый", "teal" to "Морской",
        "green" to "Зелёный", "lime" to "Салатовый", "orange" to "Оранжевый", "red" to "Красный",
        "pink" to "Розовый", "purple" to "Фиолетовый", "gray" to "Серый", "white" to "Белый",
    )

    fun of(key: String): Color = map[key] ?: AppColors.Accent
}

private val scheme = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = Color(0xFF062A28),
    primaryContainer = AppColors.AccentContainer,
    onPrimaryContainer = AppColors.OnAccentContainer,
    secondary = AppColors.Accent,
    background = AppColors.Background,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Background,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.Card,
    onSurfaceVariant = AppColors.TextSecondary,
    surfaceContainer = AppColors.Surface,
    surfaceContainerLow = AppColors.Surface,
    surfaceContainerHigh = AppColors.Card,
    surfaceContainerHighest = AppColors.Card,
    outline = AppColors.CardBorder,
    outlineVariant = AppColors.Divider,
    error = AppColors.Danger,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp),
)

@Composable
fun FileManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
