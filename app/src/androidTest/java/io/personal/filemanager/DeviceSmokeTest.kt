package io.personal.filemanager

import android.graphics.Bitmap
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.FixMethodOrder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Проверка на эмуляторе в CI: запуск, переходы по основным экранам с реальными тестовыми файлами
 * (создаются скриптом CI в Download/pfm-fixtures, личных данных нет), скриншоты экранов.
 * Не заменяет проверку на телефоне владельца.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val shots = File(SHOTS_DIR).apply { mkdirs() }

    // Each instrumented method can reuse the same app process and its navigation state.
    // Restore the home route before each test instead of assuming the previous one succeeded.
    @Before
    fun returnToHome() {
        fun onHome(): Boolean = compose.onAllNodesWithText("Мои файлы")
            .fetchSemanticsNodes().isNotEmpty()
        fun resetHomeScroll() {
            if (compose.onAllNodesWithTag("home-content-list").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("home-content-list").performScrollToIndex(0)
                compose.waitForIdle()
            }
        }
        compose.waitForIdle()
        if (onHome()) {
            resetHomeScroll()
            return
        }
        repeat(8) {
            val homeTab = compose.onAllNodesWithTag("bottom-tab-FILES")
                .fetchSemanticsNodes()
            if (homeTab.isNotEmpty()) {
                compose.onNodeWithTag("bottom-tab-FILES").performClick()
            } else {
                compose.activityRule.scenario.onActivity {
                    it.onBackPressedDispatcher.onBackPressed()
                }
            }
            compose.waitForIdle()
            if (onHome()) {
                resetHomeScroll()
                return
            }
        }
        throw AssertionError("Не удалось вернуться на главный экран перед UI-тестом")
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        // Compose v2 иногда не получает redraw на медленном эмуляторе планшета
        // в отведённые библиотекой 2 секунды. Только для screenshot используем
        // системный UiAutomation как резерв; остальные UI-assertions не ослабляем.
        val bitmap = try {
            compose.onRoot().captureToImage().asAndroidBitmap()
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: throw timeout
        }
        File(shots, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 90, it)) {
                "Не удалось сохранить тестовый скриншот $name"
            }
        }
    }

    private fun waitText(text: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun openTile(title: String) {
        // Prefer the stable bottom navigation for configured sections.
        val tab = when (title) {
            "Новые" -> "RECENT"
            "Приложения" -> "APPS"
            else -> null
        }
        if (tab != null && compose.onAllNodesWithTag("bottom-tab-$tab").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("bottom-tab-$tab").performClick()
        } else {
            // Other shortcuts can be below the fold of the home LazyColumn.
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(title))
            compose.onAllNodesWithText(title).onFirst().performClick()
        }
        compose.waitForIdle()
    }

    @Test
    fun a_home() {
        waitText("Мои файлы")
        // Regression: a bottom tab must never stretch to the full screen and cover Home.
        val rootHeight = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        val tabHeight = compose.onNodeWithTag("bottom-tab-FILES")
            .fetchSemanticsNode().boundsInRoot.height
        org.junit.Assert.assertTrue(
            "Bottom tab covers the screen: tab=$tabHeight, root=$rootHeight",
            tabHeight > 0f && tabHeight < rootHeight / 6f,
        )
        compose.onRoot().printToLog("PFM_HOME_TREE")
        try {
            waitText("Внутренняя память")
        } catch (error: Throwable) {
            shot("failure-home-memory")
            throw error
        }
        shot("d01_home")
    }

    /** Реальная проверка нового сценария: создать JSON, вставить, форматировать, сохранить. */
    /** Запрет свайпа между главными разделами и разрешение только внутри вкладок приложений. */
    @Test
    fun zz_swipeOnlyInsideApplications() {
        fun stage(where: String) = Log.i("PFM_UI", "a1_swipe: $where")
        stage("start")
        waitText("Мои файлы")
        stage("home visible")
        // Do not start near Android's back-gesture edge.
        compose.onRoot().performTouchInput {
            swipeLeft(startX = center.x * 2f * 0.75f, endX = center.x * 2f * 0.25f)
        }
        stage("home swipe complete")
        waitText("Мои файлы")
        stage("home remained visible")
        // Exercise the real bottom navigation, avoiding an unrelated lazy-home scroll.
        compose.onNodeWithTag("bottom-tab-APPS").performClick()
        stage("apps opened using bottom navigation")
        compose.onNodeWithTag("apps-tab-installed").assertIsSelected()
        stage("installed selected")
        // First verify the two explicit tabs actually control PagerState.
        compose.onNodeWithTag("apps-tab-apks").performClick()
        compose.onNodeWithTag("apps-tab-apks").assertIsSelected()
        stage("APK selected by tap")
        compose.onNodeWithTag("apps-tab-installed").performClick()
        compose.onNodeWithTag("apps-tab-installed").assertIsSelected()
        stage("installed restored by tap")
        // Move across most of the pager (not a short/fling-like swipe in a nested LazyColumn).
        compose.onNodeWithTag("apps-swipe-content").performTouchInput {
            swipeLeft(durationMillis = 350)
        }
        stage("apps swipe left complete")
        compose.waitUntil(12_000) {
            compose.onAllNodesWithTag("apps-tab-apks").fetchSemanticsNodes().any { node ->
                node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Selected) &&
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.Selected]
            }
        }
        compose.onNodeWithTag("apps-tab-apks").assertIsSelected()
        stage("APK selected")
        compose.onNodeWithTag("apps-swipe-content").performTouchInput {
            swipeRight(durationMillis = 350)
        }
        stage("apps swipe right complete")
        compose.waitUntil(12_000) {
            compose.onAllNodesWithTag("apps-tab-installed").fetchSemanticsNodes().any { node ->
                node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Selected) &&
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.Selected]
            }
        }
        compose.onNodeWithTag("apps-tab-installed").assertIsSelected()
        stage("done")
    }

    @Test
    fun aa_createAndEditJson() {
        val file = File("/sdcard/Download/pfm-editor-ci-${System.nanoTime()}.json")
        // Уникальное имя: не затрагиваем существующие файлы даже при ручном запуске теста.
        try {
            waitText("Мои файлы")
            compose.onNodeWithContentDescription("Меню").performClick()
            compose.onNodeWithText("Создать файл или папку").performClick()
            compose.onNodeWithText("JSON").performClick()
            compose.onNodeWithText("Имя файла с расширением").performTextClearance()
            compose.onNodeWithText("Имя файла с расширением").performTextInput(file.name)
            compose.onAllNodes(hasText("Создать") and hasClickAction()).onFirst().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("""{"server":{"port":443,"active":true}}""")
            waitText("Вставить")
            waitText("Копировать всё")
            compose.onNodeWithText("Формат JSON").performClick()
            compose.onNodeWithContentDescription("Сохранить").performClick()
            compose.waitUntil(15_000) {
                file.exists() && file.readText().contains("\n") &&
                    file.readText().contains("\"port\": 443")
            }
            shot("d01b_json_editor")
        } finally {
            // Удаляем только созданный этим тестом одноразовый файл.
            file.delete()
        }
    }

    /** Многострочный Markdown: нижняя панель «Вставить», «Копировать всё», «Сохранить». */
    @Test
    fun ab_pasteAndCopyAllMarkdown() {
        val file = File("/sdcard/Download/pfm-editor-clipboard-" + System.nanoTime() + ".md")
        val body = "# Заголовок\n" + "Текст с переносами\n".repeat(2_000)
        try {
            compose.activityRule.scenario.onActivity { activity ->
                activity.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("fixture", body))
            }
            waitText("Мои файлы")
            compose.onNodeWithContentDescription("Меню").performClick()
            compose.onNodeWithText("Создать файл или папку").performClick()
            compose.onNodeWithText("Markdown").performClick()
            compose.onNodeWithText("Имя файла с расширением").performTextClearance()
            compose.onNodeWithText("Имя файла с расширением").performTextInput(file.name)
            compose.onAllNodes(hasText("Создать") and hasClickAction()).onFirst().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Вставить").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Вставить").performClick()
            compose.onNodeWithText("Копировать всё").performClick()
            var copied: String? = null
            compose.activityRule.scenario.onActivity { activity ->
                copied = activity.getSystemService(ClipboardManager::class.java)
                    .primaryClip?.getItemAt(0)?.text?.toString()
            }
            org.junit.Assert.assertEquals(body, copied)
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(15_000) { file.exists() && file.readText() == body }
        } finally {
            file.delete()
        }
    }

    @Test
    fun b_recentAndText() {
        openTile("Новые")
        compose.onRoot().printToLog("PFM_RECENT_TREE")
        waitText("заметка-тест.md", 20_000)
        shot("d02_recent")
        compose.onNodeWithText("заметка-тест.md").performClick()
        waitText("Проверка кириллицы")
        shot("d03_text_utf8")
        back()
        compose.onNodeWithText("cp1251.txt").performClick()
        waitText("Привет из Windows-1251")
        shot("d04_text_cp1251")
    }

    @Test
    fun c_video() {
        openTile("Видео")
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("test-1080p.mp4")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("test-1080p.mp4").performClick()
        Thread.sleep(3_000)
        shot("d05_video")
    }

    @Test
    fun d_photo() {
        openTile("Фото")
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("photo.jpg")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("photo.jpg").performClick()
        Thread.sleep(1_500)
        shot("d06_photo")
    }

    @Test
    fun e_archive() {
        openTile("Архивы")
        waitText("fixture.zip", 20_000)
        compose.onNodeWithText("fixture.zip").performClick()
        waitText("readme.txt")
        shot("d07_archive")
    }

    @Test
    fun e2_pdf() {
        openTile("Документы")
        waitText("doc.pdf", 20_000)
        compose.onNodeWithText("doc.pdf").performClick()
        waitText("Страница 1 из 1")
        Thread.sleep(1_000)
        shot("d07b_pdf")
    }

    @Test
    fun f_notesCreate() {
        openTile("Заметки")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Новая заметка") or hasContentDescription("Новая заметка")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasText("Новая заметка") or hasContentDescription("Новая заметка")).onFirst().performClick()
        // Редактор заметки загружает данные асинхронно: ждём именно поле ввода.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Заголовок").fetchSemanticsNodes().isNotEmpty()
        }
        waitText("Вставить")
        waitText("Копировать всё")
        compose.onNodeWithText("Заголовок").performTextInput("Проверка CI")
        compose.onNodeWithText("Текст заметки").performTextInput("Текст заметки из теста")
        compose.onNodeWithText("Копировать всё").performClick()
        var copiedNote: String? = null
        compose.activityRule.scenario.onActivity { activity ->
            copiedNote = activity.getSystemService(ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.text?.toString()
        }
        org.junit.Assert.assertEquals("# Проверка CI\n\nТекст заметки из теста", copiedNote)
        Thread.sleep(1_500) // автосохранение
        shot("d08_note_editor")
        back()
        waitText("Проверка CI")
        shot("d09_notes_list")
    }

    @Test
    fun g_menuScreens() {
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText("Резервные копии").performClick()
        waitText("Ручная копия")
        shot("d10_backup")
        back()
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText("Корзина").performClick()
        waitText("Корзина")
        shot("d11_trash")
        back()
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText("Настройки").performClick()
        waitText("Удаление и корзина")
        compose.onNodeWithText("Главная и размер плиток").performClick()
        waitText("Иконки разделов")
        shot("d12_tiles")
    }

    @Test
    fun g2_settingsAndAnalyzer() {
        waitText("Мои файлы")
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText("Настройки").performClick()
        waitText("Использовать корзину")
        waitText("Показывать корзину в меню")
        waitText("Открыть корзину")
        shot("d12a_settings")
        back()
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText("Анализ памяти").performClick()
        waitText("Память устройства")
        // Полный анализ доступного хранилища на эмуляторе может занимать больше 40 с.
        // UI проверяем отдельно от длительного вычисления (результаты проверяют unit-тесты).
        compose.waitUntil(12_000) {
            compose.onAllNodesWithText("Идёт анализ:", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Размеры найденных файлов").fetchSemanticsNodes().isNotEmpty()
        }
        shot("d12b_analyzer")
        // Проверяем управление длительной операцией, не требуя её мгновенного завершения.
        if (compose.onAllNodesWithText("Остановить").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Остановить").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Сканирование отменено").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("Размеры найденных файлов").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun h_browserAndApps() {
        compose.onNodeWithText("Внутренняя память").performClick()
        waitText("Download")
        shot("d13_browser")
        back()
        openTile("Приложения")
        waitText("Установленные")
        shot("d14_apps")
    }
}
