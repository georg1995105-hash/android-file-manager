package io.personal.filemanager.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import io.personal.filemanager.core.FileTypes
import io.personal.filemanager.data.FileEntry
import java.io.File

/** Системные действия с файлами: открыть во внешнем приложении, поделиться, настройки разрешений. */
object FileActions {

    private fun authority(context: Context) = "${context.packageName}.files"

    fun mimeOf(entry: FileEntry): String = mimeOf(entry.name, entry.mimeType)

    fun mimeOf(name: String, known: String? = null): String {
        val ext = FileTypes.extensionOf(name)
        return FileTypes.fallbackMime(ext)
            ?: known?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /** content:// URI через FileProvider; null, если путь вне разрешённых корней. */
    fun contentUri(context: Context, path: String): Uri? =
        runCatching { FileProvider.getUriForFile(context, authority(context), File(path)) }.getOrNull()

    fun openExternally(context: Context, entry: FileEntry) {
        val uri = contentUri(context, entry.path) ?: return toast(context, "Не удалось получить ссылку на файл")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeOf(entry))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Неизвестный тип: даём выбрать любое приложение.
            val generic = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(
                    Intent.createChooser(generic, "Открыть с помощью").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                toast(context, "Нет приложения для открытия этого файла. Его можно отправить через «Поделиться».")
            }
        } catch (e: SecurityException) {
            toast(context, "Android не разрешил открыть файл: ${e.message ?: ""}".trim())
        }
    }

    fun share(context: Context, entries: List<FileEntry>) {
        val files = entries.filter { !it.isDirectory }
        if (files.isEmpty()) return toast(context, "Папки отправить нельзя — выберите файлы")
        val uris = files.mapNotNull { contentUri(context, it.path) }
        if (uris.size != files.size) return toast(context, "Часть файлов недоступна для отправки")
        val mimes = files.map { mimeOf(it) }.distinct()
        val mime = when {
            mimes.size == 1 -> mimes.first()
            mimes.map { it.substringBefore('/') }.distinct().size == 1 -> mimes.first().substringBefore('/') + "/*"
            else -> "*/*"
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mime
        // ClipData нужен, чтобы временные права на чтение получили все URI.
        val clip = ClipData.newRawUri(files.first().name, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val title = if (files.size == 1) "Поделиться: ${files.first().name}" else "Поделиться файлами (${files.size})"
        try {
            context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast(context, "Нет приложений для отправки")
        }
    }

    /** Экран системы «Доступ ко всем файлам» для нашего приложения. */
    fun openAllFilesAccessSettings(context: Context) {
        val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                toast(context, "Откройте Настройки → Приложения → Мои файлы → Доступ ко всем файлам")
            }
        }
    }

    fun openAppDetails(context: Context, packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(context, "Не удалось открыть сведения о приложении")
        }
    }

    fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}
