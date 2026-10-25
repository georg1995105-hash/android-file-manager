package io.personal.filemanager

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.rules.ActivityScenarioRule
import java.io.File

/**
 * Скриншоты пишутся в скрытую папку: медиатека их не индексирует, и они не попадают в «Фото»/«Новые»,
 * которые проверяют сами тесты.
 */
const val SHOTS_DIR = "/sdcard/Download/.pfm-test-shots"
const val FIXTURES_DIR = "/sdcard/Download/pfm-fixtures"

typealias MainRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

fun MainRule.shot(name: String) {
    waitForIdle()
    Thread.sleep(400)
    val dir = File(SHOTS_DIR).apply { mkdirs() }
    val bmp = onRoot().captureToImage().asAndroidBitmap()
    File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
}

fun MainRule.waitText(text: String, timeoutMs: Long = 10_000) {
    waitUntil(timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
}

fun MainRule.textShown(text: String): Boolean = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

fun MainRule.back() {
    activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
}

/** Плитка главной (может быть ниже края экрана). */
fun MainRule.openTile(title: String) {
    onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(title))
    onAllNodesWithText(title).onFirst().performClick()
    waitForIdle()
}
