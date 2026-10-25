package io.personal.filemanager

import android.app.UiAutomation
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Запускается, когда устройство повёрнуто горизонтально (CI задаёт поворот через adb): главная и
 * просмотр фото/видео остаются горизонтальными, после возврата ориентация сохраняется.
 */
@RunWith(AndroidJUnit4::class)
class OrientationTest {
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(compose).around(object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            runCatching { compose.shot("FAIL_orientation") }
        }
    })

    private fun assertLandscape(where: String) {
        compose.waitForIdle()
        var orientation = 0
        compose.activityRule.scenario.onActivity { orientation = it.resources.configuration.orientation }
        assertEquals("ориентация: $where", Configuration.ORIENTATION_LANDSCAPE, orientation)
    }

    private fun isLandscape(): Boolean {
        var o = 0
        compose.activityRule.scenario.onActivity { o = it.resources.configuration.orientation }
        return o == Configuration.ORIENTATION_LANDSCAPE
    }

    @Test
    fun landscapeKeptInViewerAndAfterReturn() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitText("Мои файлы")
        // Устройство горизонтально: телефон поворачивается на 90°, планшет уже горизонтален.
        if (!isLandscape()) automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
        else automation.setRotation(UiAutomation.ROTATION_FREEZE_CURRENT)
        try {
            compose.waitUntil(10_000) { isLandscape() }
            compose.waitText("Мои файлы")
            runChecks()
        } finally {
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    private fun runChecks() {
        assertLandscape("главная")
        compose.shot("l01_home")
        compose.openTile("Фото")
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("photo.jpg")).fetchSemanticsNodes().isNotEmpty() }
        compose.shot("l02_photos")
        compose.onNodeWithContentDescription("photo.jpg").performClick()
        Thread.sleep(1_500)
        assertLandscape("просмотр фото")
        compose.shot("l03_photo")
        compose.back()
        assertLandscape("после возврата из фото")
        compose.back()
        compose.openTile("Видео")
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("test-1080p.mp4")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("test-1080p.mp4").performClick()
        Thread.sleep(3_000)
        assertLandscape("просмотр видео")
        compose.shot("l04_video")
        compose.back()
        assertLandscape("после возврата из видео")
        compose.shot("l05_back")
    }
}
