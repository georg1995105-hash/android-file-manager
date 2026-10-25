package io.personal.filemanager.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/** Встроенный набор значков: ключ хранения → векторная иконка. Ключи совпадают с core/IconKeys. */
@Suppress("DEPRECATION")
object IconCatalog {
    private val map: Map<String, ImageVector> = mapOf(
        "download" to Icons.Filled.Download,
        "cloud_download" to Icons.Filled.CloudDownload,
        "file_download" to Icons.Filled.FileDownload,
        "save_alt" to Icons.Filled.SaveAlt,
        "arrow_downward" to Icons.Filled.ArrowDownward,
        "schedule" to Icons.Filled.Schedule,
        "event" to Icons.Filled.Event,
        "access_time" to Icons.Filled.AccessTime,
        "history" to Icons.Filled.History,
        "new_releases" to Icons.Filled.NewReleases,
        "image" to Icons.Filled.Image,
        "photo" to Icons.Filled.Photo,
        "landscape" to Icons.Filled.Landscape,
        "photo_camera" to Icons.Filled.PhotoCamera,
        "camera" to Icons.Filled.Camera,
        "photo_library" to Icons.Filled.PhotoLibrary,
        "movie" to Icons.Filled.Movie,
        "play_arrow" to Icons.Filled.PlayArrow,
        "videocam" to Icons.Filled.Videocam,
        "movie_creation" to Icons.Filled.MovieCreation,
        "play_circle" to Icons.Filled.PlayCircle,
        "video_library" to Icons.Filled.VideoLibrary,
        "description" to Icons.Filled.Description,
        "notes" to Icons.Filled.Notes,
        "article" to Icons.Filled.Article,
        "menu_book" to Icons.Filled.MenuBook,
        "text_snippet" to Icons.Filled.TextSnippet,
        "picture_as_pdf" to Icons.Filled.PictureAsPdf,
        "folder_zip" to Icons.Filled.FolderZip,
        "folder" to Icons.Filled.Folder,
        "folder_open" to Icons.Filled.FolderOpen,
        "inventory" to Icons.Filled.Inventory2,
        "storage" to Icons.Filled.Storage,
        "archive" to Icons.Filled.Archive,
        "unarchive" to Icons.Filled.Unarchive,
        "android" to Icons.Filled.Android,
        "apps" to Icons.Filled.Apps,
        "install_mobile" to Icons.Filled.InstallMobile,
        "widgets" to Icons.Filled.Widgets,
        "smartphone" to Icons.Filled.Smartphone,
        "sticky_note" to Icons.Filled.StickyNote2,
        "edit_note" to Icons.Filled.EditNote,
        "bookmark" to Icons.Filled.Bookmark,
        "attach_file" to Icons.Filled.AttachFile,
        "push_pin" to Icons.Filled.PushPin,
        "link" to Icons.Filled.Link,
        "public" to Icons.Filled.Public,
        "language" to Icons.Filled.Language,
        "code" to Icons.Filled.Code,
        "terminal" to Icons.Filled.Terminal,
        "data_object" to Icons.Filled.DataObject,
        "star" to Icons.Filled.Star,
        "favorite" to Icons.Filled.Favorite,
        "grade" to Icons.Filled.Grade,
        "home" to Icons.Filled.Home,
        "work" to Icons.Filled.Work,
        "lock" to Icons.Filled.Lock,
        "music_note" to Icons.Filled.MusicNote,
        "sd_storage" to Icons.Filled.SdStorage,
        "cloud" to Icons.Filled.Cloud,
        "inbox" to Icons.Filled.Inbox,
    )

    fun of(key: String): ImageVector = map[key] ?: Icons.Filled.Folder

    fun contains(key: String): Boolean = key in map
}
