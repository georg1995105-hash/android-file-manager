package io.personal.filemanager.ui

import io.personal.filemanager.ui.viewer.findActivity
import androidx.lifecycle.ViewModelStoreOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.personal.filemanager.AppContainer
import io.personal.filemanager.FileManagerApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as FileManagerApp).container

/** ViewModel с доступом к контейнеру зависимостей; живёт в своей записи навигации. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key) { create(container) }
}

/**
 * ViewModel разделов нижней панели: живёт, пока открыто приложение, а не только пока открыта вкладка.
 * При возврате на вкладку список виден сразу и тихо обновляется, без повторной загрузки с нуля.
 */
@Composable
inline fun <reified VM : ViewModel> appSharedViewModel(key: String, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    val owner = LocalContext.current.findActivity() as? ViewModelStoreOwner
    return if (owner != null) viewModel(viewModelStoreOwner = owner, key = key) { create(container) } else viewModel(key = key) { create(container) }
}
