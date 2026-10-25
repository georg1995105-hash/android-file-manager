package io.personal.filemanager.data.settings

enum class RecentSort(val title: String) {
    ADDED("По дате добавления"),
    MODIFIED("По дате изменения"),
}

enum class BrowserSort(val title: String) {
    NAME("По имени"),
    DATE("По дате изменения"),
    SIZE("По размеру"),
    TYPE("По типу"),
}

data class BrowserPrefs(
    val sort: BrowserSort = BrowserSort.NAME,
    val descending: Boolean = false,
    val showHidden: Boolean = false,
)
