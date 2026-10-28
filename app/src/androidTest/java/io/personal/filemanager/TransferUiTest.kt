package io.personal.filemanager

import android.content.Intent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun transferButtonsRespondToRemoteCenter() {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(rule.activity.packageName)
        assertNotNull(rule.activity.packageManager.resolveActivity(launch, 0))
        rule.waitText("Мои файлы")
        rule.openTile("Передача")
        rule.onNodeWithText("Включить передачу").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithText("Включить передачу").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitText("Остановить передачу", 15000)
        rule.onNodeWithText("Новый код (2 минуты)").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithText("Новый код (2 минуты)").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("Остановить передачу").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithText("Остановить передачу").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitText("Включить передачу")
    }
}
