package io.personal.filemanager

import android.app.Application
import coil3.ImageLoader
import io.personal.filemanager.ui.components.MediaThumbFetcher
import io.personal.filemanager.data.links.SiteIconLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import io.personal.filemanager.data.AppsRepository
import io.personal.filemanager.data.FileSystemRepository
import io.personal.filemanager.data.MediaRepository
import io.personal.filemanager.data.PlaybackPositions
import io.personal.filemanager.data.StorageRepository
import io.personal.filemanager.data.ops.OperationsManager
import io.personal.filemanager.data.db.PersonalRepository
import io.personal.filemanager.data.backup.BackupManager
import io.personal.filemanager.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Простой контейнер зависимостей без DI-фреймворка. */
class AppContainer(val app: Application) {
    /** Область для коротких фоновых записей, которые должны пережить уход с экрана. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val playback = PlaybackPositions(app)
    val storage = StorageRepository(app)
    val media = MediaRepository(app)
    val fileSystem = FileSystemRepository()
    val apps = AppsRepository(app)
    val personal = PersonalRepository(app)
    val operations = OperationsManager(app, appScope, personal)
    val backups = BackupManager(app, personal, settings)
    val transfer = io.personal.filemanager.data.transfer.TransferManager(app)
    val siteIcons = SiteIconLoader(app, appScope)
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
            .components {
                add(VideoFrameDecoder.Factory())
                add(MediaThumbFetcher.Factory())
                add(MediaThumbFetcher.Key())
            }
            .build()
}
