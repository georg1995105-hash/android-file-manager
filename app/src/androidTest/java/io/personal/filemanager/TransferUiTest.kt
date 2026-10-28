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
        // A remote or keyboard leaves touch mode; only then Compose buttons take D-pad focus (phones start in touch mode).
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(rule.activity.packageName)
        assertNotNull(rule.activity.packageManager.resolveActivity(launch, 0))
        rule.waitText("Мои файлы")
        rule.openTile("Передача")
        // Открытие раздела само включает передачу; дальше выключение и включение — кнопкой OK пульта.
        rule.waitText("Остановить передачу", 15000)
        rule.remoteCenter("Остановить передачу")
        rule.waitText("Включить передачу")
        rule.remoteCenter("Включить передачу")
        rule.waitText("Остановить передачу", 15000)
        rule.remoteCenter("Остановить передачу")
        rule.waitText("Включить передачу")
    }

    private fun MainRule.remoteCenter(text: String) {
        onNodeWithText(text).performScrollTo()
        onNodeWithText(text).performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText(text).performKeyInput { pressKey(Key.DirectionCenter) }
    }
}
