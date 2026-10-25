package io.personal.filemanager.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce

/**
 * Обновление при каждом возврате на экран и при изменениях индекса, пока экран виден.
 * Пока приложение в фоне — ничего не опрашивается.
 */
@OptIn(FlowPreview::class)
@Composable
fun RefreshWhileVisible(changes: Flow<Unit>?, onRefresh: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(owner, changes) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refresh()
            changes?.debounce(1_000)?.collect { refresh() }
        }
    }
}
