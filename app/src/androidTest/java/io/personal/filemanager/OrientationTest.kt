package io.personal.filemanager

import android.content.res.Configuration
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Запускается, когда устройство повёрнуто горизонтально (CI задаёт поворот через adb): главная и
 * просмотр фото/видео остаются горизонтальными, после возврата ориентация сохраняется.
 */
@RunWith(AndroidJUnit4::class)
class OrientationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun assertLandscape(where: String) {
        compose.waitForIdle()
        var orientation = 0
        compose.activityRule.scenario.onActivity { orientation = it.resources.configuration.orientation }
        assertEquals("ориентация: $where", Configuration.ORIENTATION_LANDSCAPE, orientation)
    }

    @Test
    fun landscapeKeptInViewerAndAfterReturn() {
        compose.waitText("Мои файлы")
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
