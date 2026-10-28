package io.personal.filemanager.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/** Поиск по текущему списку: значок в заголовке открывает поле, фильтр — подстрока без учёта регистра. */
class ListSearchState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")

    fun matches(vararg texts: String?): Boolean {
        val q = query.trim()
        return q.isEmpty() || texts.any { it?.contains(q, ignoreCase = true) == true }
    }

    fun toggle() {
        open = !open
        if (!open) query = ""
    }
}

@Composable
fun rememberListSearch(): ListSearchState = remember { ListSearchState() }

@Composable
fun ListSearchButton(state: ListSearchState) {
    IconButton(onClick = state::toggle) {
        Icon(
            if (state.open) Icons.Filled.Close else Icons.Filled.Search,
            contentDescription = if (state.open) "Закрыть поиск" else "Поиск",
        )
    }
}

@Composable
fun ListSearchField(state: ListSearchState, placeholder: String = "Поиск по имени") {
    if (!state.open) return
    val focus = remember { FocusRequester() }
    OutlinedTextField(
        value = state.query,
        onValueChange = { state.query = it },
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}
