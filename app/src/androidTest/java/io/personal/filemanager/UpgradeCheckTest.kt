package io.personal.filemanager

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверка «обновление поверх» (CI): [seed] запускается на ПРЕДЫДУЩЕЙ опубликованной сборке,
 * затем новая сборка ставится поверх без удаления, и [verify] проверяет, что заметка осталась.
 * Использует только элементы интерфейса, которые есть в обеих версиях.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeCheckTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitText(text: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun seed() {
        waitText("Заметки")
        compose.onAllNodesWithText("Заметки").onFirst().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Новая заметка") or hasContentDescription("Новая заметка")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasText("Новая заметка") or hasContentDescription("Новая заметка")).onFirst().performClick()
        compose.onNodeWithText("Заголовок").performTextInput(TITLE)
        compose.onNodeWithText("Текст заметки").performTextInput("создана в предыдущей версии")
        Thread.sleep(1_500) // автосохранение
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitText(TITLE)
    }

    @Test
    fun verify() {
        waitText("Заметки")
        compose.onAllNodesWithText("Заметки").onFirst().performClick()
        waitText(TITLE)
    }

    private companion object {
        const val TITLE = "До обновления"
    }
}
