package io.personal.filemanager

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import io.personal.filemanager.data.AppsRepository
import io.personal.filemanager.data.FileSystemRepository
import io.personal.filemanager.data.MediaRepository
import io.personal.filemanager.data.StorageRepository
import io.personal.filemanager.data.settings.SettingsRepository

/** Простой контейнер зависимостей без DI-фреймворка. */
class AppContainer(app: Application) {
    val settings = SettingsRepository(app)
    val storage = StorageRepository(app)
    val media = MediaRepository(app)
    val fileSystem = FileSystemRepository()
    val apps = AppsRepository(app)
}

class FileManagerApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
