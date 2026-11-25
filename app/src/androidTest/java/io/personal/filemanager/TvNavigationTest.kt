package io.personal.filemanager

import android.content.Intent
import androidx.compose.ui.graphics.toPixelMap
import io.personal.filemanager.ui.theme.AppColors
import kotlin.math.abs
import org.junit.Assert.assertEquals
import android.content.pm.PackageManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real D-pad events on Android TV emulators. No touch or onClick shortcuts.
 * Deletion uses isolated disposable fixtures, never personal files.
 */
@RunWith(AndroidJUnit4::class)
class TvNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun remoteCenterOnTag(tag: String) {
        val node = rule.onNodeWithTag(tag)
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        node.assertIsFocused()
        node.performKeyInput { pressKey(Key.DirectionCenter) }
    }

    private fun remoteCenterOnDescription(description: String) {
        val node = rule.onNodeWithContentDescription(description)
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        node.assertIsFocused()
        node.performKeyInput { pressKey(Key.DirectionCenter) }
    }

    private fun remoteCenterOnText(text: String) {
        val node = rule.onNodeWithText(text)
        node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        node.assertIsFocused()
        node.performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun tvLauncherAndBottomTabsRespondToRemote() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setPackage(rule.activity.packageName)
        val pm = rule.activity.packageManager
        assertNotNull(pm.resolveActivity(intent, 0))
        assertTrue(
            "Missing implicit Android TV launch resolution",
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).any {
                it.activityInfo.packageName == rule.activity.packageName &&
                    it.activityInfo.name == MainActivity::class.java.name
            },
        )
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag("hub-tab-SETTINGS").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("hub-tab-LINKS").assertDoesNotExist()
        rule.onNodeWithText("Ссылки").assertDoesNotExist()
        remoteCenterOnTag("hub-tab-SETTINGS")
        rule.onNodeWithTag("hub-tab-SETTINGS").assertIsSelected()
        remoteCenterOnTag("hub-tab-STORAGE")
        rule.onNodeWithTag("hub-tab-STORAGE").assertIsSelected()
    }

    /** Verify actual spatial D-pad focus traversal; direct RequestFocus would hide broken TV navigation. */
    @Test fun tvDpadDownMovesFocusBetweenTabs() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag("hub-tab-SETTINGS").fetchSemanticsNodes().isNotEmpty()
        }
        val first = rule.onNodeWithTag("hub-tab-STORAGE")
        first.performSemanticsAction(SemanticsActions.RequestFocus)
        first.assertIsFocused()
        first.performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("hub-tab-TRANSFER").assertIsFocused()
        rule.onNodeWithTag("hub-tab-TRANSFER").performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        rule.onNodeWithTag("hub-tab-TRANSFER").assertIsSelected()
    }

    @Test fun tvSettingsAndUpdateControlsRespondToRemote() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag("hub-tab-SETTINGS").fetchSemanticsNodes().isNotEmpty()
        }
        remoteCenterOnTag("hub-tab-SETTINGS")
        rule.onNodeWithText("Версия и обновления").assertExists()
        remoteCenterOnText("Версия и обновления")
        rule.onNodeWithText("Обновления").assertExists()
        rule.onNodeWithText("Проверить обновления").assertExists()
    }
    @Test fun receivedFileCanBeDeletedWithRemoteWithoutOpeningIt() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val app = rule.activity.application as FileManagerApp
        val dir = java.io.File(app.container.transfer.receivedDirectory, "pfm-delete-" + java.util.UUID.randomUUID())
        check(dir.mkdirs())
        val target = java.io.File(dir, "delete-test.apk").apply { writeText("disposable fixture") }
        val keep = java.io.File(dir, "keep-test.txt").apply { writeText("keep") }
        try {
            rule.waitText("Полученные файлы")
            remoteCenterOnText("Полученные файлы")
            rule.waitText(dir.name)
            rule.onAllNodesWithText(dir.name).onFirst().performClick()
            rule.waitUntil(20_000) {
                rule.onAllNodesWithContentDescription("Удалить «${target.name}»").fetchSemanticsNodes().isNotEmpty()
            }
            remoteCenterOnDescription("Удалить «${target.name}»")
            rule.onNodeWithText("Удалить файл?").assertExists()
            rule.onNodeWithText("Отмена").performSemanticsAction(SemanticsActions.RequestFocus)
            rule.onNodeWithText("Отмена").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
            assertTrue(target.exists())
            remoteCenterOnDescription("Удалить «${target.name}»")
            rule.onNodeWithText("Удалить навсегда").performSemanticsAction(SemanticsActions.RequestFocus)
            rule.onNodeWithText("Удалить навсегда").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
            rule.waitUntil(20_000) { !target.exists() }
            rule.waitUntil(20_000) {
                rule.onAllNodesWithContentDescription("Удалить «${target.name}»").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(keep.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Check rendered edges rather than only focus semantics. */
    private fun accentEdgePixels(node: SemanticsNodeInteraction): Int {
        val image = node.captureToImage().toPixelMap()
        val accent = AppColors.Accent
        var count = 0
        for (y in 0 until minOf(5, image.height)) {
            for (x in image.width / 4 until image.width * 3 / 4) {
                val color = image[x, y]
                if (abs(color.red - accent.red) < 0.08f &&
                    abs(color.green - accent.green) < 0.08f &&
                    abs(color.blue - accent.blue) < 0.08f) count++
            }
        }
        return count
    }

    @Test fun localStorageUnfocusedTilesHaveNoAccentOutline() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        rule.waitText("Файлы устройства")
        remoteCenterOnText("Файлы устройства")
        rule.waitText("Загрузки")
        val downloads = rule.onNodeWithText("Загрузки")
        val photos = rule.onNodeWithText("Фото")
        downloads.performScrollTo()
        downloads.performSemanticsAction(SemanticsActions.RequestFocus)
        downloads.assertIsFocused()
        photos.assertIsNotFocused()
        assertEquals("Unfocused tile has a bright outline", 0, accentEdgePixels(photos))
        assertTrue("Focused tile has no visible outline", accentEdgePixels(downloads) > 0)
        photos.performSemanticsAction(SemanticsActions.RequestFocus)
        photos.assertIsFocused()
        downloads.assertIsNotFocused()
        assertEquals("Old focus outline remained on tile", 0, accentEdgePixels(downloads))
        assertTrue("New focus outline missing", accentEdgePixels(photos) > 0)
    }
}
