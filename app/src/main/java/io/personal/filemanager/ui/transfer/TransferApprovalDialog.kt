package io.personal.filemanager.ui.transfer

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.personal.filemanager.ui.appContainer

/** Запрос другого устройства (подключение или приём файлов) — поверх любого экрана приложения. */
@Composable
fun TransferApprovalDialog() {
    val manager = appContainer().transfer
    val state by manager.state.collectAsStateWithLifecycle()
    val request = state.approvals.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Передача файлов") },
        text = { Text(request.description) },
        confirmButton = { Button(onClick = { manager.approve(request.id, true) }) { Text("Разрешить") } },
        dismissButton = { TextButton(onClick = { manager.approve(request.id, false) }) { Text("Отклонить") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}
