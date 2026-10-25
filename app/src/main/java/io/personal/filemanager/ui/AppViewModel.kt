package io.personal.filemanager.ui

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
