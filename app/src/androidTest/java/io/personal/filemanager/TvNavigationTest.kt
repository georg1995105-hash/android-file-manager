package io.personal.filemanager

import android.content.Intent
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
 * The test does not read, alter, or delete any personal files.
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
            rule.onAllNodesWithTag("bottom-tab-RECENT").fetchSemanticsNodes().isNotEmpty()
        }
        remoteCenterOnTag("bottom-tab-RECENT")
        rule.onNodeWithTag("bottom-tab-RECENT").assertIsSelected()
        remoteCenterOnTag("bottom-tab-FILES")
        rule.onNodeWithTag("bottom-tab-FILES").assertIsSelected()
    }

    /** Verify actual spatial D-pad focus traversal; direct RequestFocus would hide broken TV navigation. */
    @Test fun tvDpadRightMovesFocusBetweenTabs() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag("bottom-tab-RECENT").fetchSemanticsNodes().isNotEmpty()
        }
        val first = rule.onNodeWithTag("bottom-tab-RECENT")
        first.performSemanticsAction(SemanticsActions.RequestFocus)
        first.assertIsFocused()
        first.performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("bottom-tab-FILES").assertIsFocused()
        rule.onNodeWithTag("bottom-tab-FILES").performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        rule.onNodeWithTag("bottom-tab-FILES").assertIsSelected()
    }

    @Test fun tvSettingsAndUpdateControlsRespondToRemote() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        rule.waitUntil(20_000) {
            rule.onAllNodesWithContentDescription("Меню").fetchSemanticsNodes().isNotEmpty()
        }
        remoteCenterOnDescription("Меню")
        remoteCenterOnText("Настройки")
        rule.onNodeWithText("Версия и обновления").assertExists()
        remoteCenterOnText("Версия и обновления")
        rule.onNodeWithText("Обновления").assertExists()
        rule.onNodeWithText("Проверить обновления").assertExists()
    }
}
