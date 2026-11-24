package io.personal.filemanager.data.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import io.personal.filemanager.data.transfer.IncomingTransfer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException

internal data class RemoteFile(val id: String, val name: String, val size: Long, val hash: String)
internal data class VaultDevice(val id: String, val name: String, val owner: Boolean)
internal data class PendingFile(val id: String, val name: String, val size: Long, val meta: String, val device: String, val endpoint: String)

internal class VaultFiles(private val context: Context, private val session: VaultSession) {
    private val api = VaultClient(session)
    private val root = File(context.filesDir, "storage-upload-queue").apply { mkdirs() }
    private fun aad(id: String, part: String) = "pfm-storage-v1/$id/$part"
    private fun path(id: String): String {
        require(id.matches(Regex("[0-9a-f]{32}")))
        return "/v1/files/$id"
    }
    private fun safeName(name: String): String {
        IncomingTransfer.validateName(name)
        return name
    }
    fun list(): List<RemoteFile> {
        val a = api.json("GET", "/v1/files").getJSONArray("files")
        return (0 until a.length()).map {
            val record = a.getJSONObject(it)
            val id = record.getString("id")
            path(id)
            val size = record.getLong("size")
            require(size in 0..VaultClient.MAX_FILE)
            val meta = JSONObject(VaultCrypto.open(session.key, aad(id, "meta"), VaultCrypto.unb64(record.getString("meta"))).toString(Charsets.UTF_8))
            require(meta.getLong("size") == size)
            val hash = meta.getString("sha256")
            require(hash.matches(Regex("[a-f0-9]{64}")))
            RemoteFile(id, safeName(meta.getString("name")), size, hash)
        }
    }
    fun devices(): List<VaultDevice> {
        val a = api.json("GET", "/v1/devices").getJSONArray("devices")
        return (0 until a.length()).map {
            val d = a.getJSONObject(it)
            VaultDevice(d.getString("id"), d.getString("name"), d.getInt("owner") == 1)
        }
    }
    fun revoke(device: VaultDevice) { api.json("DELETE", "/v1/devices/" + device.id) }
    fun delete(file: RemoteFile) { api.json("DELETE", path(file.id)) }
    private fun readJob(dir: File): PendingFile {
        val j = JSONObject(AtomicFile(File(dir, "job.json")).readFully().toString(Charsets.UTF_8))
        return PendingFile(dir.name, j.getString("name"), j.getLong("size"), j.getString("meta"), j.getString("device"), j.getString("endpoint"))
    }
    fun pending(): List<PendingFile> = root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{32}")) }
        .mapNotNull { runCatching { readJob(it) }.getOrNull() }
        .filter { it.device == session.device && it.endpoint == session.endpoint }
    suspend fun enqueue(uri: Uri): PendingFile {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.let { safeName(it) } ?: error("Не удалось определить имя файла")
        val id = VaultCrypto.hex(VaultCrypto.randomBytes(16))
        val dir = File(root, id).apply { check(mkdir()) }
        try {
            val sha = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val input = resolver.openInputStream(uri) ?: error("Не удалось открыть файл")
            input.use { source ->
                File(dir, "payload").outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buf)
                        if (n < 0) break
                        size += n
                        require(size <= VaultClient.MAX_FILE && dir.usableSpace > 32 * 1024 * 1024) { "Недостаточно места или файл слишком большой" }
                        out.write(buf, 0, n); sha.update(buf, 0, n)
                    }
                    out.fd.sync()
                }
            }
            val meta = JSONObject().put("name", name).put("size", size).put("sha256", VaultCrypto.hex(sha.digest()))
            val sealed = VaultCrypto.b64(VaultCrypto.seal(session.key, aad(id, "meta"), meta.toString().toByteArray()))
            val j = JSONObject().put("name", name).put("size", size).put("meta", sealed)
                .put("device", session.device).put("endpoint", session.endpoint)
            val atomic = AtomicFile(File(dir, "job.json"))
            val output = atomic.startWrite()
            try { output.write(j.toString().toByteArray()); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
            return readJob(dir)
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }
    suspend fun upload(job: PendingFile, progress: (Long,Long)->Unit) {
        require(job.device == session.device && job.endpoint == session.endpoint)
        val dir = File(root, job.id)
        val payload = File(dir, "payload")
        require(payload.isFile && payload.length() == job.size) { "Локальная копия отсутствует" }
        val url = path(job.id)
        val initial = api.json("POST", url, JSONObject().put("size", job.size).put("meta", job.meta))
        val chunks = (job.size + VaultClient.CHUNK - 1) / VaultClient.CHUNK
        var index = initial.getLong("next")
        require(index in 0..chunks)
        if (!initial.optBoolean("done")) {
            RandomAccessFile(payload, "r").use { source ->
                source.seek(minOf(job.size, index * VaultClient.CHUNK))
                progress(source.filePointer, job.size)
                while (index < chunks) {
                    currentCoroutineContext().ensureActive()
                    val block = ByteArray(minOf(VaultClient.CHUNK.toLong(), job.size - source.filePointer).toInt())
                    source.readFully(block)
                    val data = VaultCrypto.seal(session.key, aad(job.id, index.toString()), block)
                    val ack = api.put("$url/$index", data).getLong("next")
                    check(ack == index + 1) { "Неверное подтверждение блока" }
                    index = ack
                    progress(source.filePointer, job.size)
                }
            }
            api.json("POST", "$url/finish", JSONObject())
        }
        dir.deleteRecursively()
    }
    fun discard(job: PendingFile) {
        require(job.device == session.device && job.endpoint == session.endpoint)
        val url = path(job.id)
        try {
            val state = api.json("GET",url)
            if (!state.optBoolean("done")) api.json("DELETE",url)
        } catch (e: VaultHttpError) { if (e.status != 404) throw e }
        check(File(root,job.id).deleteRecursively()) { "Не удалось очистить очередь" }
    }
    suspend fun download(file: RemoteFile, directory: File, progress: (Long,Long)->Unit): File {
        safeName(file.name)
        check(directory.isDirectory || directory.mkdirs()) { "Нет доступа к папке" }
        require(directory.usableSpace >= file.size + 16 * 1024 * 1024) { "Недостаточно места" }
        // Persist only authenticated whole chunks in app-private staging, then publish a verified copy.
        val downloadRoot = File(context.filesDir, "storage-download-queue").apply { check(isDirectory || mkdirs()) }
        val staging = File(downloadRoot, VaultCrypto.hash((session.endpoint + "/" + session.device + "/" + file.id + "/" + file.hash).toByteArray()))
        val sha = MessageDigest.getInstance("SHA-256")
        var verified = false
        try {
            RandomAccessFile(staging,"rw").use { partial ->
                var done = partial.length().coerceAtMost(file.size)
                if (done != file.size) done -= done % VaultClient.CHUNK
                partial.setLength(done)
                partial.seek(0)
                var left = done
                val buffer = ByteArray(256 * 1024)
                while (left > 0) {
                    currentCoroutineContext().ensureActive()
                    val n = minOf(left,buffer.size.toLong()).toInt()
                    partial.readFully(buffer,0,n); sha.update(buffer,0,n); left -= n
                }
                progress(done,file.size)
                var index = done / VaultClient.CHUNK
                while (done < file.size) {
                    currentCoroutineContext().ensureActive()
                    val expected = minOf(VaultClient.CHUNK.toLong(), file.size - done).toInt()
                    val data = api.get(path(file.id) + "/" + index, expected + 28)
                    require(data.size == expected + 28)
                    val plain = VaultCrypto.open(session.key, aad(file.id,index.toString()), data)
                    require(plain.size == expected)
                    partial.write(plain); partial.fd.sync()
                    sha.update(plain); done += plain.size; index++
                    progress(done,file.size)
                }
            }
            if (VaultCrypto.hex(sha.digest()) != file.hash) {
                staging.delete()
                error("Контрольная сумма файла не совпала; загрузку нужно повторить")
            }
            verified = true
        } catch (e: javax.crypto.AEADBadTagException) { staging.delete(); throw e }
        check(verified)
        val temp = File.createTempFile(".storage-", ".part", directory)
        try {
            staging.inputStream().use { input -> temp.outputStream().use { out -> input.copyTo(out); out.fd.sync() } }
            val dot = file.name.lastIndexOf('.').takeIf { it > 0 } ?: file.name.length
            for (n in 0..9999) {
                val name = if (n == 0) file.name else file.name.substring(0,dot) + " ($n)" + file.name.substring(dot)
                val target = File(directory,name)
                try {
                    Files.move(temp.toPath(), target.toPath())
                    staging.delete()
                    return target
                } catch (_: FileAlreadyExistsException) { /* never overwrite */ }
            }
            error("Не удалось выбрать свободное имя")
        } finally { temp.delete() }
    }
}
