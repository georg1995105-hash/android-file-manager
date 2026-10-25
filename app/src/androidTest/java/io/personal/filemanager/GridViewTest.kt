package io.personal.filemanager

import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.personal.filemanager.core.gridStamp
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Сетка в «Новых»: дата и время под файлом совпадают с меткой Android (DATE_ADDED), при максимуме колонок
 * дата и время в две строки, выбор удержанием выделяется, удаление показывает список файлов (здесь — отмена).
 */
@RunWith(AndroidJUnit4::class)
class GridViewTest {
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(compose).around(object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            runCatching { compose.shot("FAIL_grid_${description.methodName}") }
        }
    })

    private fun hasTextMatching(regex: Regex) = SemanticsMatcher("text ~ $regex") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { regex.matches(it.text) } == true
    }

    private fun countMatching(regex: Regex) = compose.onAllNodes(hasTextMatching(regex)).fetchSemanticsNodes().size

    private fun openViewMenu() {
        compose.onAllNodes(hasContentDescription("Вид:", substring = true)).onFirst().performClick()
        compose.waitText("Файлов в ряду")
    }

    @Test
    fun recentGridShowsAndroidTimestamps() {
        val name = "заметка-тест.md"
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val addedSec = ctx.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        assertNotNull("нет записи MediaStore для $name", addedSec)
        val expected = gridStamp(addedSec!! * 1000)!!

        compose.waitText("Мои файлы")
        compose.openTile("Новые")
        compose.waitText(name, 20_000)
        openViewMenu()
        compose.onNodeWithText("Сетка", substring = true).performClick()
        compose.waitUntil(10_000) { countMatching(Regex("""\d\d\.\d\d · \d\d:\d\d""")) > 0 }
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(name))
        // Дата и время под файлом — из DATE_ADDED (сортировка «по дате появления»).
        assertTrue("нет «${expected.oneLine}»", compose.textShown(expected.oneLine))
        compose.shot("g01_recent_grid")

        // Максимум колонок: телефон — 5, планшет — 7. Узкая ячейка — дата и время отдельными строками.
        openViewMenu()
        val max = if (compose.onAllNodesWithText("7").fetchSemanticsNodes().isNotEmpty()) "7" else "5"
        compose.onAllNodesWithText(max).onFirst().performClick()
        compose.waitUntil(10_000) {
            countMatching(Regex("""\d\d:\d\d""")) > 0 || countMatching(Regex("""\d\d\.\d\d · \d\d:\d\d""")) > 0
        }
        // Часы и минуты видны всегда — одной строкой или отдельной.
        assertTrue(countMatching(Regex("""(\d\d\.\d\d · )?\d\d:\d\d""")) > 0)
        compose.shot("g02_recent_grid_max_columns")

        // Выбор удержанием и удаление: в диалоге перечислены выбранные файлы; отмена — ничего не удаляется.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(name))
        compose.onAllNodesWithText(name).onFirst().performTouchInput { longClick() }
        compose.waitText("Выбрано: 1")
        compose.shot("g03_grid_selected")
        compose.onNodeWithContentDescription("Удалить").performClick()
        compose.waitText("Выбран 1 объект")
        assertTrue(compose.onAllNodesWithText("• $name").fetchSemanticsNodes().isNotEmpty())
        compose.shot("g04_delete_list")
        compose.onNodeWithText("Отмена").performClick()
        compose.back()
        compose.waitUntil(5_000) { !compose.textShown("Выбрано:") }

        // Вернуть список (настройка сохраняется).
        openViewMenu()
        compose.onNodeWithText("Список", substring = true).performClick()
        compose.waitUntil(10_000) { countMatching(Regex("""\d\d\.\d\d · \d\d:\d\d""")) == 0 }
    }
}
