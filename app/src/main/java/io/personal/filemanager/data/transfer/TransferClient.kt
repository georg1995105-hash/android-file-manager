package io.personal.filemanager.data.transfer

import java.io.*
import java.net.*
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.*
import org.json.JSONObject
import kotlinx.coroutines.*

/** Native Android/TV client of exactly the same API used by the browser. */
class TransferClient(address: String, private val pin: String? = null) {
    val base: String
    private val host: String
    private val port: Int
    @Volatile private var active: HttpsURLConnection? = null
    var token = ""
    private var batch = ""
    fun beginBatch() { batch = api("/batch", "POST").getString("id") }
    init {
        val uri = URI(address.trim().trimEnd('/'))
        require(uri.scheme == "https" && uri.host?.matches(Regex("[0-9.]+")) == true && uri.port in 1..65535 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty()) { "Введите https://локальный-IP:порт" }
        val ip = InetAddress.getByName(uri.host)
        require(ip.isSiteLocalAddress || ip.isLinkLocalAddress || ip.isLoopbackAddress) { "Нужен локальный адрес" }
        host = uri.host; port = uri.port; base = uri.toString()
    }
    private fun fingerprint(cert: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
    private fun ssl(inspect: ((String) -> Unit)? = null): SSLContext {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, type: String) { error("Client certificates not accepted") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, type: String) {
                require(chain.isNotEmpty()); chain[0].checkValidity()
                val actual = fingerprint(chain[0])
                if (inspect != null) inspect(actual) else require(actual == pin) { "Сертификат устройства изменился. Требуется новая сверка" }
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    }
    // Один SSLContext на клиента: TLS-сессия переиспользуется, и каждый блок не делает полное рукопожатие заново.
    private val pinnedFactory by lazy { ssl().socketFactory }
    fun inspect(): String {
        var actual = ""
        val socket = ssl { actual = it }.socketFactory.createSocket() as SSLSocket
        socket.use { it.soTimeout = 10_000; it.connect(InetSocketAddress(host, port), 10_000); it.startHandshake() }
        return actual
    }
    fun cancel() { active?.disconnect() }
    private fun connection(path: String, method: String): HttpsURLConnection {
        require(pin != null)
        return (URL(base + path).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinnedFactory
            // The exact certificate pin, approved by the user, replaces hostname PKI for numeric LAN addresses.
            hostnameVerifier = HostnameVerifier { name, _ -> name == host }
            instanceFollowRedirects = false; connectTimeout = 10_000; readTimeout = 30_000
            requestMethod = method; useCaches = false
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            active = this
        }
    }
    fun api(path: String, method: String = "GET", value: JSONObject? = null, bytes: ByteArray? = null, headers: Map<String, String> = emptyMap()): JSONObject {
        val c = connection(path, method)
        try {
            headers.forEach { (key, v) -> c.setRequestProperty(key, v) }
            if (method in listOf("POST", "PUT")) {
                val data = bytes ?: value?.toString()?.toByteArray() ?: byteArrayOf()
                c.doOutput = true; c.setFixedLengthStreamingMode(data.size)
                c.outputStream.use { it.write(data) }
            }
            val status = c.responseCode
            val input = if (status in 200..299) c.inputStream else c.errorStream
            val data = input?.use { it.boundedBytes(1024 * 1024 + 1) } ?: byteArrayOf()
            require(data.size <= 1024 * 1024) { "Слишком большой ответ" }
            val result = JSONObject(data.toString(Charsets.UTF_8))
            require(status in 200..299) { result.optString("error", "Ошибка HTTP $status") }
            return result
        } finally { c.disconnect(); active = null }
    }
    suspend fun pair(code: String, name: String) {
        val pending = api("/pair", "POST", JSONObject().put("code", code).put("name", name)).getString("id")
        repeat(120) {
            currentCoroutineContext().ensureActive()
            val result = api("/pair/$pending")
            if (!result.isNull("token")) { token = result.getString("token"); return }
            delay(1000)
        }
        error("Подключение не подтверждено")
    }
    suspend fun send(item: TransferManager.Shared, progress: (String) -> Unit) {
        require(item.size == item.file.length() && item.modified == item.file.lastModified()) { "Файл изменился" }
        val key = api("/offer", "POST", JSONObject().put("name", item.name).put("size", item.size).put("sha256", item.hash).put("batch", batch)).getString("id")
        try {
            var accepted = false
            for (attempt in 0 until 300) {
                currentCoroutineContext().ensureActive()
                if (api("/upload/$key").getBoolean("accepted")) { accepted = true; break }
                progress("Подтвердите приём ${item.name} на другом устройстве"); delay(1000)
            }
            check(accepted) { "Время подтверждения истекло" }
            item.file.inputStream().use { input ->
                var offset = 0L
                // 4 МиБ — предел сервера: вчетверо меньше запросов, чем блоками по 1 МиБ.
                val buffer = ByteArray(IncomingTransfer.MAX_CHUNK)
                while (offset < item.size) {
                    currentCoroutineContext().ensureActive()
                    val count = input.readChunk(buffer, minOf(buffer.size.toLong(), item.size - offset).toInt())
                    check(count > 0) { "Исходный файл изменился" }
                    val chunk = buffer.copyOf(count)
                    val hash = MessageDigest.getInstance("SHA-256").digest(chunk).joinToString("") { "%02x".format(it) }
                    var acknowledged = false
                    for (attempt in 0..4) {
                        currentCoroutineContext().ensureActive()
                        try {
                            api("/upload/$key", "PUT", bytes = chunk, headers = mapOf("X-Offset" to "$offset", "X-SHA256" to hash))
                            acknowledged = true; break
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            // Обрыв: блок мог дойти — сверяемся с подтверждённым смещением и при необходимости повторяем.
                            val position = runCatching { api("/upload/$key").getLong("offset") }.getOrNull()
                            if (position == offset + count) { acknowledged = true; break }
                            if (attempt == 4) throw e
                            delay(1000)
                        }
                    }
                    check(acknowledged); offset += count; progress("${item.name}: $offset / ${item.size}")
                }
            }
            api("/upload/$key", "POST")
            progress("Получен и проверен: ${item.name}")
        } catch (e: Exception) {
            runCatching { api("/upload/$key", "DELETE") }
            throw e
        }
    }
    suspend fun receive(file: JSONObject, manager: TransferManager, progress: (String) -> Unit) {
        val name = file.getString("name"); val size = file.getLong("size")
        val target = manager.receivedDirectory
        val staging = manager.stagingDirectory
        target.mkdirs()
        IncomingTransfer(staging.toPath(), target.toPath(), name, size, file.getString("sha256"), target.usableSpace - 64L * 1024 * 1024, true).renameOnConflict().use { receiver ->
            val path = api("/ticket/${file.getString("id")}", "POST").getString("url")
            require(path.matches(Regex("/download/[a-f0-9]{64}")))
            val c = connection(path, "GET")
            try {
                check(c.responseCode == 200 && c.contentLengthLong == size) { "Размер ответа изменился" }
                c.inputStream.use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (receiver.offset() < size) {
                        currentCoroutineContext().ensureActive()
                        val n = input.readChunk(buffer, minOf(buffer.size.toLong(), size - receiver.offset()).toInt())
                        check(n > 0) { "Соединение оборвалось" }
                        val chunk = buffer.copyOf(n)
                        val hash = MessageDigest.getInstance("SHA-256").digest(chunk).joinToString("") { "%02x".format(it) }
                        receiver.append(receiver.offset(), n, hash, ByteArrayInputStream(chunk))
                        progress("$name: ${receiver.offset()} / $size")
                    }
                    val saved = receiver.finish()
                    manager.rescan(saved.toString())
                    progress("Сохранено: ${saved.fileName} → Загрузки/Передача")
                }
            } finally { c.disconnect(); active = null }
        }
    }
}

private fun InputStream.readChunk(buffer: ByteArray, limit: Int): Int {
    var count = 0
    while (count < limit) {
        val n = read(buffer, count, limit - count)
        if (n < 0) break
        if (n == 0) continue
        count += n
    }
    return count
}
private fun InputStream.boundedBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (n < 0) break
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
