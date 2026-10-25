package io.personal.filemanager

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Просмотр и выбор на тестовых файлах Download/pfm-fixtures (создаются в CI, личных данных нет):
 * перелистывание, автоскрытие панелей, жесты видео, удаление в корзину (только trash-me.jpg),
 * выбор удержанием, удержание плитки не открывает раздел.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ViewerSelectionTest {
    val compose = createAndroidComposeRule<MainActivity>()

    /** При падении — скриншот текущего экрана (до закрытия активности). */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(compose).around(object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            runCatching { compose.shot("FAIL_${description.methodName}") }
        }
    })

    private fun openFixture(name: String) {
        compose.waitText("Мои файлы")
        compose.onNodeWithText("Внутренняя память").performClick()
        compose.waitText("Download")
        compose.onAllNodesWithText("Download").onFirst().performClick()
        compose.waitText("pfm-fixtures")
        compose.onAllNodesWithText("pfm-fixtures").onFirst().performClick()
        compose.waitText("cp1251.txt")
        // Файл может быть ниже края экрана (планшет горизонтально) — сначала прокрутка.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(androidx.compose.ui.test.hasText(name))
        compose.waitText(name)
        compose.onAllNodesWithText(name).onFirst().performClick()
    }

    private fun controlsShown() = compose.onAllNodesWithContentDescription("Назад").fetchSemanticsNodes().isNotEmpty()

    /** Дождаться автоскрытия и коснуться экрана — панели снова видны. */
    private fun showControls() {
        compose.waitUntil(8_000) { !controlsShown() }
        compose.onRoot().performTouchInput { click(center) }
        compose.waitUntil(5_000) { controlsShown() }
    }

    @Test
    fun a_swipeAutoHideAndVideoGestures() {
        openFixture("photo.jpg")
        compose.waitText("1 из 4")
        compose.shot("v01_viewer_photo")
        // Автоскрытие (ТЗ: 2–3 с) и возврат одним касанием.
        compose.waitUntil(8_000) { !controlsShown() }
        compose.shot("v02_controls_hidden")
        compose.onRoot().performTouchInput { click(center) }
        compose.waitUntil(5_000) { controlsShown() }
        // Свайп — следующий файл.
        compose.onRoot().performTouchInput { swipeLeft() }
        showControls()
        compose.waitText("2 из 4")
        assertTrue(compose.textShown("photo2.jpg"))
        // Следующий — видео: воспроизводится, двойное касание справа — +10 с.
        compose.onRoot().performTouchInput { swipeLeft() }
        Thread.sleep(2_500)
        showControls()
        compose.waitText("3 из 4")
        assertTrue(
            compose.onAllNodes(hasContentDescription("Пауза") or hasContentDescription("Воспроизвести"))
                .fetchSemanticsNodes().isNotEmpty(),
        )
        // Видео действительно подготовлено: на ползунке известна длительность тестового ролика (6 с).
        compose.waitText("0:06", 10_000)
        compose.shot("v03_viewer_video")
        compose.onRoot().performTouchInput { doubleClick(centerRight.copy(x = width * 0.8f)) }
        compose.waitText("+10 с", 3_000)
    }

    @Test
    fun b_selectionByLongPress() {
        compose.waitText("Мои файлы")
        // Удержание плитки не открывает раздел.
        compose.onAllNodesWithText("Документы").onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(compose.textShown("Разделы"))
        compose.openTile("Фото")
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("photo.jpg")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("photo.jpg").performTouchInput { longClick() }
        compose.waitText("Выбрано: 1")
        compose.onNodeWithContentDescription("photo2.jpg").performClick()
        compose.waitText("Выбрано: 2")
        compose.shot("v04_selection")
        compose.back()
        compose.waitUntil(5_000) { !compose.textShown("Выбрано:") }
        // «Назад» снял выбор, а не закрыл раздел.
        assertTrue(compose.onAllNodes(hasContentDescription("photo.jpg")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun c_deleteToTrashOnTestFileOnly() {
        val target = File("$FIXTURES_DIR/trash-me.jpg")
        assertTrue("нет тестового файла", target.exists())
        openFixture("trash-me.jpg")
        compose.waitText("4 из 4")
        compose.onNodeWithContentDescription("В корзину").performClick()
        compose.waitText("Переместить в корзину?")
        compose.shot("v05_delete_confirm")
        compose.onNodeWithText("В корзину").performClick()
        compose.waitUntil(10_000) { !target.exists() }
        assertFalse(target.exists())
        val trash = File("/sdcard/.FileManagerTrash")
        assertTrue("файл не попал в корзину", trash.walkTopDown().any { it.name == "trash-me.jpg" })
    }
}
