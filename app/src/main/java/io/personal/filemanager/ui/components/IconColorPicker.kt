package io.personal.filemanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.personal.filemanager.core.ColorKeys
import io.personal.filemanager.core.IconKeys
import io.personal.filemanager.ui.icons.IconCatalog
import io.personal.filemanager.ui.theme.AccentColors
import io.personal.filemanager.ui.theme.AppColors

/** Квадратная кнопка-значок как в макете «Иконки разделов»; выбранная — с синей рамкой и галочкой. */
@Composable
fun IconChoice(
    iconKey: String,
    colorKey: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    Box(modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.Surface)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) AppColors.Accent else AppColors.CardBorder,
                    shape = RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onClick)
                .semantics { this.selected = selected },
            contentAlignment = Alignment.Center,
        ) {
            SectionIcon(iconKey, if (selected) colorKey else "white", size * 0.5f)
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = AppColors.Accent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp)
                    .background(AppColors.Background, CircleShape),
            )
        }
    }
}

/** Полный встроенный набор значков и палитра цветов. Изменения применяются сразу. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IconColorPickerSheet(
    title: String,
    iconKey: String,
    colorKey: String,
    onChange: (icon: String, color: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = AppColors.Surface) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionIcon(iconKey, colorKey, 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            Text("Цвет", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ColorKeys.all.forEach { key ->
                    val selected = key == colorKey
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(AccentColors.of(key))
                            .border(if (selected) 3.dp else 0.dp, if (selected) Color.White else Color.Transparent, CircleShape)
                            .clickable { onChange(iconKey, key) }
                            .semantics {
                                contentDescription = AccentColors.names[key] ?: key
                                this.selected = selected
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Значок", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconKeys.all.filter { IconCatalog.contains(it) }.forEach { key ->
                    IconChoice(key, colorKey, selected = key == iconKey, onClick = { onChange(key, colorKey) }, size = 52.dp)
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
        }
    }
}
