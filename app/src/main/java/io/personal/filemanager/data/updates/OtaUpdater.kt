package io.personal.filemanager.data.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.personal.filemanager.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class OtaRelease(val version: String, val versionCode: Long, val apkUrl: String,
                      val sha256: String, val size: Long)

/**
 * Updates only from official stable GitHub Releases: verify SHA-256, package ID,
 * monotonically increasing versionCode and the existing production signing certificate.
 * The app is NEVER uninstalled or its data cleared.
 */
class OtaUpdater(private val context: Context) {
    companion object {
        const val API = "https://api.github.com/repos/georg1995105-hash/android-file-manager/releases/latest"
        const val CERT = "d282ae0bb49c30bd848fcae3ae02533ff446a12ef7e3691863af53c4aa0ecda7"
        const val MAX_APK = 200L * 1024L * 1024L
    }

    // CI signs and publishes APKs to apk-downloads even if GitHub Release publishing fails.
    // Prefer that channel, with GitHub Releases as a backwards-compatible fallback.
    suspend fun latest(): OtaRelease? = withContext(Dispatchers.IO) {
        val direct = runCatching { latestFromDirectBranch() }
        val releases = runCatching { latestFromReleases() }
        listOfNotNull(direct.getOrNull(), releases.getOrNull())
            .maxByOrNull { it.versionCode }?.let { return@withContext it }
        // The Releases fallback can lag behind the direct channel. Never hide a direct-channel error.
        if (direct.isSuccess) return@withContext null
        throw IllegalStateException("Не удалось проверить основной канал обновлений: ${direct.exceptionOrNull()?.message}", direct.exceptionOrNull())
    }

    private fun latestFromDirectBranch(): OtaRelease? {
        val base = "https://raw.githubusercontent.com/georg1995105-hash/android-file-manager/apk-downloads/"
        val fresh = "?ota=" + System.currentTimeMillis()
        val entries = org.json.JSONArray(read(base + "RELEASE_IDENTITY.json" + fresh, 64 * 1024))
        val record = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter {
                it.optString("package") == context.packageName &&
                    it.optString("certificate_sha256").lowercase() == CERT &&
                    it.optBoolean("signature_verified") && !it.optBoolean("debuggable") &&
                    it.optString("abi") == "universal"
            }
            .maxByOrNull { it.optLong("version_code", -1L) } ?: return null
        val version = record.optLong("version_code", -1L)
        if (version <= BuildConfig.VERSION_CODE.toLong()) return null
        val name = record.optString("file")
        if (!Regex("personal-file-manager-[0-9A-Za-z.]+-release-vc\\d+-universal\\.apk").matches(name))
            throw SecurityException("Неверное имя опубликованного APK")
        if (!name.contains("-vc$version-universal.apk"))
            throw SecurityException("Версия APK в метаданных не совпадает")
        val sha = record.optString("sha256").lowercase()
        if (!sha.matches(Regex("[a-f0-9]{64}"))) throw SecurityException("Неверный SHA-256 APK")
        val published = read(base + "SHA256SUMS.txt" + fresh, 65536).lineSequence().any {
            it.trim() == "$sha  $name"
        }
        if (!published) throw SecurityException("SHA-256 APK не подтверждён публикацией")
        return OtaRelease(
            version = record.optString("version_name"),
            versionCode = version,
            apkUrl = base + name,
            sha256 = sha,
            size = -1L, // published identity does not carry asset size; bounded by MAX_APK
        )
    }

    private fun latestFromReleases(): OtaRelease? {
        val json = JSONObject(read(API, 512 * 1024))
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val assets = json.getJSONArray("assets")
        var apkName = ""
        var apkLink = ""
        var checksumLink = ""
        var size = -1L
        var versionCode = -1L
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name == "SHA256SUMS.txt") checksumLink = asset.optString("browser_download_url")
            val code = Regex("-release-vc(\\d+)-universal\\.apk$").find(name)
                ?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (code > versionCode) {
                apkName = name
                apkLink = asset.optString("browser_download_url")
                size = asset.optLong("size", -1L)
                versionCode = code
            }
        }
        if (versionCode <= BuildConfig.VERSION_CODE.toLong()) return null
        if (apkName.isBlank() || checksumLink.isBlank() || size <= 0 || size > MAX_APK)
            throw IllegalStateException("В релизе нет универсального APK и контрольной суммы")
        checkUrl(apkLink)
        checkUrl(checksumLink)
        val line = read(checksumLink, 65536).lineSequence()
            .map(String::trim).firstOrNull { it.endsWith("  $apkName") || it.endsWith(" *$apkName") }
            ?: throw SecurityException("Нет опубликованного SHA-256 для APK")
        val hash = line.take(64).lowercase()
        if (!hash.matches(Regex("[0-9a-f]{64}"))) throw SecurityException("Неверный SHA-256")
        return OtaRelease(
            json.optString("name").ifBlank { json.optString("tag_name") },
            versionCode, apkLink, hash, size,
        )
    }

    suspend fun download(release: OtaRelease, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            checkUrl(release.apkUrl)
            val dir = File(context.cacheDir, "ota").also { it.mkdirs() }
            val tmp = File(dir, "update.part.apk")
            val target = File(dir, "verified-${release.versionCode}.apk")
            tmp.delete()
            val conn = connect(release.apkUrl)
            try {
                if (conn.responseCode !in 200..299) error("Скачивание: HTTP ${conn.responseCode}")
                val sha = MessageDigest.getInstance("SHA-256")
                val reportedSize = if (release.size > 0) release.size else conn.contentLengthLong
                var bytes = 0L
                var last = -1
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            bytes += n
                            if (bytes > MAX_APK || (release.size > 0 && bytes > release.size)) throw SecurityException("Неверный размер APK")
                            sha.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            val percent = if (reportedSize > 0) ((100 * bytes) / reportedSize).toInt().coerceAtMost(99) else -1
                            if (percent >= 0 && percent != last) {
                                last = percent
                                withContext(Dispatchers.Main) { onProgress(percent) }
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (bytes < 1024L || (release.size > 0 && bytes != release.size))
                    throw SecurityException("APK загружен не полностью")
                val hash = sha.digest().joinToString("") { "%02x".format(it) }
                if (hash != release.sha256) throw SecurityException("SHA-256 APK не совпадает")
                verify(tmp, release.versionCode)
                if (target.exists() && !target.delete()) error("Не удалось заменить временный APK")
                if (!tmp.renameTo(target)) error("Не удалось сохранить APK")
                withContext(Dispatchers.Main) { onProgress(100) }
                target
            } catch (e: Exception) {
                tmp.delete()
                throw e
            } finally {
                conn.disconnect()
            }
        }

    suspend fun cached(release: OtaRelease): File? = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "ota/verified-${release.versionCode}.apk")
        if (!file.isFile) return@withContext null
        try {
            verifyDownloaded(file, release)
            file
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun verifyDownloaded(file: File, release: OtaRelease) {
        if (!file.isFile || file.length() !in 1024L..MAX_APK ||
            (release.size > 0 && file.length() != release.size)) error("APK отсутствует или повреждён")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != release.sha256)
            throw SecurityException("SHA-256 APK не совпадает")
        verify(file, release.versionCode)
    }

    suspend fun install(file: File, release: OtaRelease): Unit = withContext(Dispatchers.IO) {
        verifyDownloaded(file, release)
        check(context.packageManager.canRequestPackageInstalls()) { "Разрешите установку из этого источника" }
        OtaInstallSession.start(context, file)
    }

    suspend fun installerIntent(file: File, release: OtaRelease): Intent = withContext(Dispatchers.IO) {
        verifyDownloaded(file, release)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = android.content.ClipData.newRawUri("APK", uri) }
    }

    private fun verify(file: File, expectedVersion: Long) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw SecurityException("Android не распознал APK")
        if (candidate.packageName != context.packageName) throw SecurityException("Другое приложение в APK")
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode
                   else candidate.versionCode.toLong()
        if (code != expectedVersion || code <= BuildConfig.VERSION_CODE.toLong())
            throw SecurityException("Неверная версия обновления")
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, flags)
        // Some vendor Android TV builds return PackageInfo but omit archive signingInfo.
        // Query the legacy platform verifier separately only when the modern result is absent.
        // Never fall back after an explicit multi-signer or mismatched-certificate result.
        fun modern(info: PackageInfo): Array<Signature>? =
            if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null
        @Suppress("DEPRECATION")
        fun legacyInstalled(): Array<Signature>? =
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        @Suppress("DEPRECATION")
        fun legacyArchive(): Array<Signature>? {
            val parsed = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
                ?: return null
            if (parsed.packageName != context.packageName) return null
            val parsedCode = if (Build.VERSION.SDK_INT >= 28) parsed.longVersionCode else parsed.versionCode.toLong()
            if (parsedCode != expectedVersion) return null
            return parsed.signatures
        }
        fun fingerprint(s: Signature): String = MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        val oldCert = otaSingleSigner(modern(installed), ::legacyInstalled)
            ?: throw SecurityException("Нельзя проверить установленную подпись")
        val newCert = otaSingleSigner(modern(candidate), ::legacyArchive)
            ?: throw SecurityException("Нельзя проверить подпись загруженного APK: Android не вернул сертификат")
        if (fingerprint(oldCert) != CERT || fingerprint(newCert) != CERT)
            throw SecurityException("Требуется исходная RELEASE-подпись. DEBUG нельзя обновить через OTA")
    }

    private fun checkUrl(s: String) {
        val url = URL(s)
        if (url.protocol != "https" || url.host !in setOf("api.github.com", "github.com", "raw.githubusercontent.com"))
            throw SecurityException("Недоверенный адрес обновления")
    }

    private fun connect(s: String): HttpsURLConnection {
        checkUrl(s)
        return (URL(s).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 30000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "PersonalFileManager-OTA")
            instanceFollowRedirects = true
        }
    }

    private fun read(url: String, limit: Int): String {
        val conn = connect(url)
        try {
            if (conn.responseCode !in 200..299) error("GitHub: HTTP ${conn.responseCode}")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > limit) throw SecurityException("Слишком большой ответ сервера")
                    out.write(buffer, 0, n)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            conn.disconnect()
        }
    }
}

/** A missing modern result permits a separately verified legacy result, not a conflicting result. */
internal fun <T> otaSingleSigner(modern: Array<T>?, legacy: () -> Array<T>?): T? =
    if (modern.isNullOrEmpty()) legacy()?.singleOrNull() else modern.singleOrNull()
