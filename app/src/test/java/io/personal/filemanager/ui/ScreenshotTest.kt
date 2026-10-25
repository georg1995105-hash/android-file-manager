package io.personal.filemanager.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.personal.filemanager.core.SectionId
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.StorageInfo
import io.personal.filemanager.data.settings.UiConfig
import io.personal.filemanager.ui.components.DateShown
import io.personal.filemanager.ui.components.FileListPane
import io.personal.filemanager.ui.home.HomeContent
import io.personal.filemanager.ui.home.HomeState
import io.personal.filemanager.ui.settings.TabEditActions
import io.personal.filemanager.ui.settings.TabSettingsContent
import io.personal.filemanager.ui.settings.TileSettingsContent
import io.personal.filemanager.ui.theme.AppColors
import io.personal.filemanager.ui.theme.FileManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Снимки экранов для сверки с утверждённым макетом (design/reference.png).
 * Данные — вымышленные образцы только для теста; в приложении демо-данных нет.
 * PNG пишутся в app/build/screenshots и публикуются CI в ветку ci-screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private object NoopNavigator : AppNavigator {
        override fun openSection(id: SectionId) = Unit
        override fun openFile(entry: FileEntry, siblings: List<FileEntry>) = Unit
        override fun openSearch() = Unit
        override fun openTileSettings() = Unit
        override fun openTabSettings() = Unit
        override fun back() = Unit
    }

    private fun millis(month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val sampleFiles = listOf(
        FileEntry("/sample/IMG_20240314_182931.jpg", "IMG_20240314_182931.jpg", false, 3_400_000, millis(3, 14, 18, 29), millis(3, 14, 18, 29)),
        FileEntry("/sample/Отчёт_2024.pdf", "Отчёт_2024.pdf", false, 2_800_000, millis(3, 14, 14, 12), millis(3, 14, 14, 12)),
        FileEntry("/sample/app-release.apk", "app-release.apk", false, 48_700_000, millis(3, 14, 11, 3), millis(3, 14, 11, 3)),
        FileEntry("/sample/Видео_отпуск.mp4", "Видео_отпуск.mp4", false, 412_000_000, millis(3, 13, 20, 21), millis(3, 13, 20, 21)),
        FileEntry("/sample/заметки.md", "заметки.md", false, 4_100, millis(3, 12, 9, 5), millis(3, 12, 9, 5)),
        FileEntry("/sample/backup.7z", "backup.7z", false, 820_000_000, millis(3, 11, 22, 40), millis(3, 11, 22, 40)),
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            FileManagerTheme {
                CompositionLocalProvider(LocalNavigator provides NoopNavigator) {
                    Box(Modifier.fillMaxSize().background(AppColors.Background)) { content() }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun home() = shot("01_home") {
        val config = UiConfig.defaults()
        Scaffold(
            containerColor = AppColors.Background,
            bottomBar = { BottomTabBar(config.tabs, SectionId.FILES) {} },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                HomeContent(
                    state = HomeState(
                        storage = StorageInfo(totalBytes = 512_000_000_000, freeBytes = 435_600_000_000),
                        hasAccess = true,
                        stats = mapOf(
                            SectionId.DOWNLOADS to "1,2 ГБ (282)",
                            SectionId.RECENT to "51,2 МБ (67)",
                            SectionId.PHOTOS to "340 МБ (274)",
                            SectionId.VIDEOS to "1,4 ГБ (271)",
                            SectionId.DOCUMENTS to "15 МБ (94)",
                            SectionId.ARCHIVES to "820 МБ (37)",
                            SectionId.APPS to "5,6 ГБ (43)",
                        ),
                        recent = sampleFiles.take(4),
                        recentLoaded = true,
                    ),
                    config = config,
                )
            }
        }
    }

    @Test
    fun homeWithoutAccess() = shot("02_home_no_access") {
        HomeContent(
            state = HomeState(storage = StorageInfo(512_000_000_000, 435_600_000_000), hasAccess = false, recentLoaded = true),
            config = UiConfig.defaults(),
        )
    }

    @Test
    fun tileSettings() = shot("03_tile_settings") {
        TileSettingsContent(UiConfig.defaults().tiles, onMove = { _, _ -> }, onUpdate = { _, _ -> }, onReset = {})
    }

    @Test
    fun tabSettings() = shot("04_tab_settings") {
        TabSettingsContent(
            draft = UiConfig.defaults().tabs,
            actions = TabEditActions({ _, _ -> }, {}, {}, { _, _, _ -> }, {}, {}),
        )
    }

    @Test
    fun recentList() = shot("05_recent_list") {
        FileListPane(entries = sampleFiles, dateShown = DateShown.ADDED)
    }
}
