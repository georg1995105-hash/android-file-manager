package io.personal.filemanager.data.links

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Значки сайтов для «Ссылок». Запрос идёт только по HTTPS и только к самому сайту ссылки (без сторонних
 * сервисов значков), без cookies и кэша HTTP. Результат хранится в памяти и в cacheDir: повторно сайт
 * не опрашивается 30 дней, после неудачи — сутки. Не более 4 загрузок одновременно, одна на сайт.
 */
class SiteIconLoader(context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.cacheDir, "site-icons")
    private val memory = LruCache<String, Bitmap>(64)
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val network = Dispatchers.IO.limitedParallelism(4)

    /** Уже загруженный значок без обращения к диску и сети (для первого кадра строки). */
    fun cached(url: String): Bitmap? = SiteIcons.hostOf(url)?.let { memory.get(it) }

    suspend fun icon(url: String): Bitmap? {
        val host = SiteIcons.hostOf(url) ?: return null
        memory.get(host)?.let { return it }
        val job = inFlight.getOrPut(host) { scope.async(network) { load(host) } }
        return try {
            job.await()
        } finally {
            inFlight.remove(host, job)
        }
    }

    private fun load(host: String): Bitmap? {
        val name = sha1(host)
        val ok = File(dir, "$name.png")
        val fail = File(dir, "$name.none")
        val now = System.currentTimeMillis()
        val cached = if (ok.exists()) BitmapFactory.decodeFile(ok.path) else null
        if (cached != null && now - ok.lastModified() < FRESH_MS) return remember(host, cached)
        if (cached == null && fail.exists() && now - fail.lastModified() < RETRY_MS) return null

        val fetched = runCatching { fetch(host) }.getOrNull()
        dir.mkdirs()
        if (fetched != null) {
            runCatching {
                val tmp = File(dir, "$name.tmp")
                tmp.outputStream().use { fetched.compress(Bitmap.CompressFormat.PNG, 100, it) }
                tmp.renameTo(ok)
            }
            fail.delete()
            return remember(host, fetched)
        }
        if (cached != null) return remember(host, cached) // сайт недоступен — оставляем прежний значок
        runCatching { fail.writeText(""); fail.setLastModified(now) }
        return null
    }

    private fun remember(host: String, bitmap: Bitmap): Bitmap {
        memory.put(host, bitmap)
        return bitmap
    }

    private fun fetch(host: String): Bitmap? {
        val page = "https://$host/"
        val candidates = runCatching {
            val (html, finalUrl) = download(page, MAX_HTML_BYTES, acceptHtml = true) ?: return@runCatching emptyList()
            SiteIcons.iconCandidates(String(html, Charsets.UTF_8), finalUrl)
        }.getOrDefault(emptyList())
        for (url in (candidates + "https://$host/favicon.ico").distinct().take(4)) {
            val bytes = runCatching { download(url, MAX_ICON_BYTES, acceptHtml = false)?.first }.getOrNull() ?: continue
            decode(bytes)?.let { return it }
        }
        return null
    }

    /** Байты и итоговый адрес после перенаправлений; null — ошибка, не https или слишком большой ответ. */
    private fun download(url: String, limit: Int, acceptHtml: Boolean): Pair<ByteArray, String>? {
        val conn = URL(url).openConnection() as? HttpURLConnection ?: return null
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) PersonalFileManager")
            conn.setRequestProperty("Accept", if (acceptHtml) "text/html" else "image/*")
            if (conn.responseCode !in 200..299) return null
            if (conn.url.protocol != "https") return null
            if (!acceptHtml && conn.contentLengthLong > limit) return null
            val bytes = conn.inputStream.use { readLimited(it, limit) } ?: return null
            return bytes to conn.url.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) return if (limit == MAX_HTML_BYTES) out.toByteArray() else null
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ICON_PX && bounds.outHeight / (sample * 2) >= ICON_PX) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (bmp.width <= ICON_PX && bmp.height <= ICON_PX) return bmp
        val scale = ICON_PX.toFloat() / maxOf(bmp.width, bmp.height)
        return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val ICON_PX = 128
        const val TIMEOUT_MS = 6_000
        const val MAX_HTML_BYTES = 256 * 1024
        const val MAX_ICON_BYTES = 512 * 1024
        const val FRESH_MS = 30L * 24 * 3600 * 1000
        const val RETRY_MS = 24L * 3600 * 1000
    }
}
