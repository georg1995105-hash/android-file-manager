package io.personal.filemanager

import android.graphics.Bitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.ui.components.MediaThumb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TvMediaTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun thumbnailsAndRemoteNextPrevious() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val context=rule.activity.applicationContext
        val dir=File((context as FileManagerApp).container.transfer.receivedDirectory,"pfm-tv-test-fixtures")
        check(!dir.exists()) {"Fixture folder already exists"}
        check(dir.mkdirs())
        try {
            val first=File(dir,"a-photo.jpg")
            val second=File(dir,"b-photo.jpg")
            for((file,color) in listOf(first to android.graphics.Color.RED,second to android.graphics.Color.BLUE)) {
                val image=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
                image.eraseColor(color)
                file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG,95,it)) }
                image.recycle()
            }
            val video=File(dir,"c-video.mp4")
            File("/sdcard/Download/pfm-tv-test.mp4").copyTo(video)
            runBlocking {
                for(file in listOf(first,second,video)) {
                    val request=ImageRequest.Builder(context).data(MediaThumb.of(FileEntry.from(file))).size(160).build()
                    assertTrue("Thumbnail failed: "+file.name,SingletonImageLoader.get(context).execute(request) is SuccessResult)
                }
            }
            rule.waitText("Полученные файлы")
            rule.onAllNodesWithText("Полученные файлы").onFirst().performClick()
            rule.waitText(dir.name)
            rule.onAllNodesWithText(dir.name).onFirst().performClick()
            rule.waitText(first.name)
            rule.onAllNodesWithText(first.name).onFirst().performClick()
            rule.waitText("1 из 3")
            val surface=rule.onNodeWithTag("tv-media-surface")
            surface.assertIsFocused()
            surface.performKeyInput{pressKey(Key.DirectionRight)}
            rule.waitText("2 из 3")
            surface.performKeyInput{pressKey(Key.DirectionRight)}
            rule.waitText("3 из 3")
            rule.waitText("0:04",15000)
            surface.performKeyInput{pressKey(Key.DirectionCenter)}
            surface.performKeyInput{pressKey(Key.DirectionLeft)}
            rule.waitText("2 из 3")
            surface.performKeyInput{pressKey(Key.DirectionDown)}
            rule.onNodeWithContentDescription("Сведения").assertIsFocused()
            rule.onNodeWithContentDescription("Сведения").performKeyInput{pressKey(Key.DirectionUp)}
            surface.assertIsFocused()
            surface.performKeyInput{pressKey(Key.DirectionLeft)}
            rule.waitText("1 из 3")
            rule.back()
        }finally{dir.deleteRecursively()}
    }
}
