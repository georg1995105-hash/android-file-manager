package io.personal.filemanager.ui.components

import android.content.ContentUris
import android.media.ThumbnailUtils
import android.os.Build
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import io.personal.filemanager.core.FileKind
import io.personal.filemanager.data.FileEntry
import java.io.File

/** Модель миниатюры для Coil: фото/видео в списках и сетках (не для полноэкранного просмотра). */
data class MediaThumb(val path: String, val mediaId: Long?, val isVideo: Boolean) {
    companion object {
        fun of(entry: FileEntry) = MediaThumb(entry.path, entry.mediaId, entry.kind == FileKind.VIDEO)
    }
}

/**
 * Миниатюры через системный кэш Android (MediaStore.loadThumbnail) — без чтения всего снимка или
 * видео при каждой прокрутке. Файлы вне индекса — ThumbnailUtils (встроенная EXIF-миниатюра/кадр).
 */
class MediaThumbFetcher(private val data: MediaThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val w = (options.size.width as? Dimension.Pixels)?.px ?: DEFAULT_PX
        val h = (options.size.height as? Dimension.Pixels)?.px ?: DEFAULT_PX
        val size = Size(w.coerceIn(32, MAX_PX), h.coerceIn(32, MAX_PX))
        val resolver = options.context.contentResolver
        val bitmap = data.mediaId?.takeIf { Build.VERSION.SDK_INT >= 29 }?.let { id ->
            val base = if (data.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            runCatching { resolver.loadThumbnail(ContentUris.withAppendedId(base, id), size, null) }.getOrNull()
        } ?: run {
            val file = File(data.path)
            if (Build.VERSION.SDK_INT >= 29) {
                if (data.isVideo) ThumbnailUtils.createVideoThumbnail(file, size, null) else ThumbnailUtils.createImageThumbnail(file, size, null)
            } else if (data.isVideo) {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
                    ?: throw java.io.IOException("Не удалось получить кадр")
            } else {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(file.absolutePath,bounds)
                var sample=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample > maxOf(size.width,size.height)*2) sample*=2
                val image=BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply{inSampleSize=sample})
                    ?: throw java.io.IOException("Не удалось получить миниатюру")
                val orientation=runCatching{ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
                val matrix=Matrix().apply{
                    when(orientation){2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f)}
                }
                if(matrix.isIdentity) image else android.graphics.Bitmap.createBitmap(image,0,0,image.width,image.height,matrix,true).also { if(it!==image)image.recycle() }
            }
        }
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher = MediaThumbFetcher(data, options)
    }

    /** Ключ кэша памяти (Coil добавляет к нему размер). */
    class Key : Keyer<MediaThumb> {
        override fun key(data: MediaThumb, options: Options): String = "thumb:${data.mediaId ?: -1}:${data.path}:${File(data.path).lastModified()}"
    }

    private companion object {
        const val DEFAULT_PX = 256
        const val MAX_PX = 512
    }
}
