package io.personal.filemanager.ui.components

import android.content.ContentUris
import android.media.ThumbnailUtils
import android.os.Build
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.provider.MediaStore
import android.util.Size
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.personal.filemanager.data.cancellableMediaRead
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
data class MediaThumb(val path: String, val mediaId: Long?, val isVideo: Boolean, val modified: Long = 0L, val bytes: Long = 0L) {
    companion object {
        fun of(entry: FileEntry) = MediaThumb(entry.path, entry.mediaId, entry.kind == FileKind.VIDEO, entry.lastModified, entry.size)
    }
}

/**
 * Миниатюры через системный кэш Android (MediaStore.loadThumbnail) — без чтения всего снимка или
 * видео при каждой прокрутке. Файлы вне индекса — ThumbnailUtils (встроенная EXIF-миниатюра/кадр).
 */
class MediaThumbFetcher(private val data: MediaThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(thumbnailIo) { cancellableMediaRead { signal ->
        val w = (options.size.width as? Dimension.Pixels)?.px ?: DEFAULT_PX
        val h = (options.size.height as? Dimension.Pixels)?.px ?: DEFAULT_PX
        val size = Size(w.coerceIn(32, MAX_PX), h.coerceIn(32, MAX_PX))
        val resolver = options.context.contentResolver
        val primary = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
        val bitmap = data.mediaId?.takeIf { Build.VERSION.SDK_INT >= 29 && data.path.startsWith(primary) }?.let { id ->
            val base = if (data.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            runCatching { resolver.loadThumbnail(ContentUris.withAppendedId(base, id), size, signal) }.getOrNull()
        } ?: run {
            signal.throwIfCanceled()
            val file = File(data.path)
            if (Build.VERSION.SDK_INT >= 29) {
                if (data.isVideo) ThumbnailUtils.createVideoThumbnail(file, size, signal) else ThumbnailUtils.createImageThumbnail(file, size, signal)
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
        if (signal.isCanceled) { bitmap.recycle(); signal.throwIfCanceled() }
        ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    } }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher = MediaThumbFetcher(data, options)
    }

    /** Ключ кэша памяти (Coil добавляет к нему размер). */
    class Key : Keyer<MediaThumb> {
        override fun key(data: MediaThumb, options: Options): String = "thumb:${data.path}:${data.modified}:${data.bytes}"
    }

    private companion object {
        const val DEFAULT_PX = 256
        const val MAX_PX = 512
    }
}

private val thumbnailIo = Dispatchers.IO.limitedParallelism(3)

data class PdfThumb(val path: String, val modified: Long, val bytes: Long) {
    companion object { fun of(entry: FileEntry) = PdfThumb(entry.path, entry.lastModified, entry.size) }
}

/** First page only, bounded resolution, read-only descriptor. */
class PdfThumbFetcher(private val data: PdfThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(thumbnailIo) {
        cancellableMediaRead { signal ->
            val w = ((options.size.width as? Dimension.Pixels)?.px ?: 192).coerceIn(32, 384)
            val h = ((options.size.height as? Dimension.Pixels)?.px ?: 192).coerceIn(32, 384)
            val fd = ParcelFileDescriptor.open(File(data.path), ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }
            val bitmap = renderer.use {
                require(it.pageCount > 0)
                it.openPage(0).use { page ->
                    val scale = minOf(w.toFloat() / page.width, h.toFloat() / page.height)
                    Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),
                        (page.height*scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { image ->
                        try { signal.throwIfCanceled(); image.eraseColor(Color.WHITE); page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                        catch(e: Exception) { image.recycle(); throw e }
                    }
                }
            }
            if(signal.isCanceled) { bitmap.recycle(); signal.throwIfCanceled() }
            ImageFetchResult(bitmap.asImage(), true, DataSource.DISK)
        }
    }
    class Factory : Fetcher.Factory<PdfThumb> {
        override fun create(data: PdfThumb, options: Options, imageLoader: ImageLoader): Fetcher = PdfThumbFetcher(data, options)
    }
    class Key : Keyer<PdfThumb> {
        override fun key(data: PdfThumb, options: Options) = "pdf:${data.path}:${data.modified}:${data.bytes}"
    }
}
