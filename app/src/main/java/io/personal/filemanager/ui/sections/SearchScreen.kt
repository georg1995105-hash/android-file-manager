package io.personal.filemanager.ui.sections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.personal.filemanager.AppContainer
import io.personal.filemanager.core.pluralRu
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.ui.LocalNavigator
import io.personal.filemanager.ui.appViewModel
import io.personal.filemanager.ui.components.AllFilesAccessRequired
import io.personal.filemanager.ui.components.DateShown
import io.personal.filemanager.ui.components.EmptyState
import io.personal.filemanager.ui.components.FileListPane
import io.personal.filemanager.ui.components.LoadingBox
import io.personal.filemanager.ui.components.ScreenTopBar
import io.personal.filemanager.ui.components.rememberAllFilesAccess
import io.personal.filemanager.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchState(val query: String = "", val loading: Boolean = false, val results: List<FileEntry>? = null)

class SearchViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null

    fun search(query: String) {
        job?.cancel()
        if (query.isBlank()) {
            _state.value = SearchState(query = query)
            return
        }
        _state.value = _state.value.copy(query = query, loading = true)
        job = viewModelScope.launch(Dispatchers.IO) {
            delay(300) // не искать на каждую букву
            val results = runCatching { container.media.search(query) }.getOrDefault(emptyList())
            _state.value = SearchState(query = query, loading = false, results = results)
        }
    }
}

/** Поиск по имени во всём индексе файлов. Поле ввода внизу — у большого пальца и над клавиатурой. */
@Composable
fun SearchScreen() {
    val navigator = LocalNavigator.current
    val hasAccess = rememberAllFilesAccess()
    val vm = appViewModel { SearchViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (text.isNotBlank() && state.results == null) vm.search(text)
        runCatching { focus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        val results = state.results
        ScreenTopBar(
            title = "Поиск",
            subtitle = when {
                results == null -> "по именам файлов во всей памяти"
                else -> "Найдено: " + pluralRu(results.size, "файл", "файла", "файлов")
            },
            onBack = { navigator.back() },
        )
        Box(Modifier.weight(1f)) {
            when {
                !hasAccess -> AllFilesAccessRequired()
                state.loading && results == null -> LoadingBox()
                results == null -> EmptyState(Icons.Filled.Search, "Что найти?", "Введите часть имени файла внизу экрана.")
                results.isEmpty() -> EmptyState(Icons.Filled.SearchOff, "Ничего не найдено", "Проверьте написание или введите другую часть имени.")
                else -> FileListPane(entries = results, dateShown = DateShown.MODIFIED)
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                vm.search(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .focusRequester(focus),
            placeholder = { Text("Имя файла") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = ""; vm.search("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Очистить")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AppColors.Card,
                unfocusedContainerColor = AppColors.Card,
                unfocusedBorderColor = AppColors.CardBorder,
            ),
        )
    }
}
