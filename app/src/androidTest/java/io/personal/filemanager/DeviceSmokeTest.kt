package io.personal.filemanager

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.FixMethodOrder
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
        // Плитка может быть ниже края экрана (ленивый список): сначала прокрутить к ней.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(title))
        compose.onAllNodesWithText(title).onFirst().performClick()
        compose.waitForIdle()
    }

    @Test
    fun a_home() {
        waitText("Мои файлы")
        waitText("Внутренняя память")
        shot("d01_home")
    }

    /** Реальная проверка нового сценария: создать JSON, вставить, форматировать, сохранить. */
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
            compose.onNodeWithText("Форматировать JSON").performClick()
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

    @Test
    fun b_recentAndText() {
        openTile("Новые")
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
        compose.onNodeWithText("Заголовок").performTextInput("Проверка CI")
        compose.onNodeWithText("Текст заметки").performTextInput("Текст заметки из теста")
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
        compose.onNodeWithText("Настроить").performClick()
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
