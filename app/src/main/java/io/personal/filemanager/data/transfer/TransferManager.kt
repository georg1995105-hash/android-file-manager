package io.personal.filemanager.data.transfer

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.net.NetworkInterface
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class TransferManager(private val context: Context) {
    data class Approval(val id: String, val description: String)
    /** [addresses] — HTTPS для приложения; [browserAddresses] — обычный HTTP для браузера по QR (без предупреждения о сертификате). */
    data class State(val running: Boolean = false, val addresses: List<String> = emptyList(), val browserAddresses: List<String> = emptyList(), val code: String = "",
        val fingerprint: String = "", val approvals: List<Approval> = emptyList(), val message: String = "",
        val shared: Int = 0, val progress: String = "")
    val discovery = TransferDiscovery(context)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private var server: LocalHttpsServer? = null
    private var browserServer: LocalHttpsServer? = null
    private val lock = Any()
    private data class Pairing(val name: String, val expires: Long, var token: String? = null)
    private data class Upload(val owner: String, val name: String, val size: Long, val hash: String, val batch: String,
        var receiver: IncomingTransfer? = null, var done: Boolean = false)
    data class Shared(val file: File, val name: String, val size: Long, val modified: Long, val hash: String)
    private val pairs = ConcurrentHashMap<String, Pairing>()
    private val sessions = ConcurrentHashMap<String, Long>()
    private val batches = ConcurrentHashMap<String, String>()
    /** Отправки, которые получатель уже принял: остальные файлы той же отправки принимаются без новых вопросов. */
    private val approvedBatches = ConcurrentHashMap.newKeySet<String>()
    private val uploads = ConcurrentHashMap<String, Upload>()
    private val shared = ConcurrentHashMap<String, Shared>()
    private val tickets = ConcurrentHashMap<String, Pair<String, String>>()
    private var attempts = 0
    private var window = 0L
    private var codeExpires = 0L
    private var generation = 0L
    /** Полученное — сразу в «Загрузки/Передача» с исходными именами (при доступе ко всем файлам), иначе в память приложения. */
    val receivedDirectory: File get() =
        if (android.os.Environment.isExternalStorageManager()) {
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Передача")
        } else File(context.filesDir, "received")
    /** Недокачанное лежит рядом с итоговой папкой (та же файловая система — атомарный перенос), скрыто точкой. */
    val stagingDirectory: File get() = File(receivedDirectory, ".staging")
    private fun id() = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private fun now() = android.os.SystemClock.elapsedRealtime()
    private fun update(block: (State) -> State) = synchronized(lock) { mutable.value = block(mutable.value) }
    fun message(text: String) = update { it.copy(message = text) }
    fun rescan(path: String) = android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    fun start() {
        scope.launch {
            runCatching {
                synchronized(lock) {
                    if (server != null) return@launch
                    File(context.filesDir, "transfer-staging").deleteRecursively()
                    stagingDirectory.deleteRecursively()
                    val (tls, fingerprint) = TransferTls.context()
                    val listener = LocalHttpsServer(tls.serverSocketFactory, ::handle)
                    server = listener
                    val browser = LocalHttpsServer(javax.net.ServerSocketFactory.getDefault(), ::handle)
                    browserServer = browser
                    discovery.advertise(listener.port())
                    codeExpires = now() + 120_000
                    val hosts = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                        .flatMap { it.inetAddresses.toList() }.filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
                        .map { it.hostAddress }
                    mutable.value = mutable.value.copy(running = true, addresses = hosts.map { "https://$it:${listener.port()}" },
                        browserAddresses = hosts.map { "http://$it:${browser.port()}" },
                        code = "%08d".format(random.nextInt(100_000_000)), fingerprint = fingerprint, message = "")
                    scope.launch { listener.run() }
                    scope.launch { browser.run() }
                }
            }.onFailure { message("Не удалось запустить: ${it.message}") }
        }
    }
    fun renewCode() = synchronized(lock) {
        codeExpires = now() + 120_000
        mutable.value = mutable.value.copy(code = "%08d".format(random.nextInt(100_000_000)))
    }
    fun stop() {
        synchronized(lock) {
            generation++
            server?.close(); server = null
            browserServer?.close(); browserServer = null
            discovery.stop()
            pairs.clear(); sessions.clear(); tickets.clear(); batches.clear(); approvedBatches.clear()
            val abandoned = uploads.values.toList(); uploads.clear()
            scope.launch { abandoned.forEach { runCatching { it.receiver?.close() } } }
            mutable.value = State(shared = shared.size, message = "Передача выключена")
        }
    }
    private fun startReceiving(upload: Upload) {
        if (upload.size > context.filesDir.usableSpace - 64L * 1024 * 1024) error("Недостаточно места")
        upload.receiver = IncomingTransfer(stagingDirectory.toPath(),
            receivedDirectory.toPath(), upload.name, upload.size, upload.hash, upload.size, true).renameOnConflict()
    }

    fun approve(id: String, accept: Boolean) {
        if (!accept) {
            pairs.remove(id)
            val receiver = uploads.remove(id)?.receiver
            update { it.copy(approvals = it.approvals.filterNot { a -> a.id == id }) }
            scope.launch { runCatching { receiver?.close() } }
            return
        }
        scope.launch {
            runCatching {
                synchronized(lock) {
                    val pair = pairs[id]
                    if (pair != null) {
                        if (accept && pair.expires > now()) {
                            val token = id(); sessions[token] = now() + 3600_000; pair.token = token
                        } else pairs.remove(id)
                    }
                    val upload = uploads[id]
                    if (upload != null) {
                        if (accept && sessions[upload.owner]?.let { it > now() } == true) {
                            startReceiving(upload)
                            approvedBatches += upload.batch
                        } else uploads.remove(id)?.receiver?.close()
                    }
                    mutable.value = mutable.value.copy(approvals = mutable.value.approvals.filterNot { it.id == id })
                }
            }.onFailure { message("Ошибка подтверждения: ${it.message}") }
        }
    }
    fun activeReceives(): List<Approval> = uploads.entries.filter { it.value.receiver != null && !it.value.done }.map { Approval(it.key, it.value.name) }
    fun cancel(id: String) = approve(id, false)
    fun share(files: List<File>) {
        scope.launch {
            runCatching {
                val snapshot = synchronized(lock) { generation }
                val found = LinkedHashMap<String, Shared>()
                for (root in files) {
                    val rootPath = root.toPath()
                    Files.walk(rootPath).use { paths ->
                        val iterator = paths.iterator()
                        while (iterator.hasNext()) {
                            val path = iterator.next()
                            if (Files.isSymbolicLink(path)) error("Символические ссылки не передаются")
                            if (!Files.isRegularFile(path)) continue
                            if (found.size >= 1000) error("За один раз можно выбрать до 1000 файлов")
                            val f = path.toFile()
                            val size = f.length(); val modified = f.lastModified()
                            val hash = digest(f)
                            check(size == f.length() && modified == f.lastModified()) { "Файл изменился: ${f.name}" }
                            val relative = if (root.isDirectory) root.name + "/" + rootPath.relativize(path) else root.name
                            found[id()] = Shared(f, relative, size, modified, hash)
                        }
                    }
                }
                synchronized(lock) {
                    check(snapshot == generation) { "Сеанс остановлен" }
                    shared.clear(); shared.putAll(found); tickets.clear()
                    mutable.value = mutable.value.copy(shared = shared.size, message = "Доступны только выбранные файлы: ${shared.size}")
                }
            }.onFailure { message("Не удалось подготовить: ${it.message}") }
        }
    }
    fun selectedFiles(): List<Shared> = shared.values.toList()
    fun clearShared() { shared.clear(); tickets.clear(); update { it.copy(shared = 0) } }
    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(65536); while (true) {
            val n = input.read(b); if (n < 0) break; digest.update(b, 0, n)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun json(out: OutputStream, status: Int = 200, obj: JSONObject = JSONObject()) =
        LocalHttpsServer.reply(out, status, "application/json; charset=utf-8", obj.toString().toByteArray())
    private fun body(r: LocalHttpsServer.Request): JSONObject {
        require(r.length <= 16384) { "Слишком большой запрос" }
        return JSONObject(r.body.readBytes().toString(Charsets.UTF_8))
    }
    private fun handle(r: LocalHttpsServer.Request, out: OutputStream) {
        try {
            if (r.method == "GET" && r.path in listOf("/", "/appjs", "/style")) {
                val asset = when (r.path) { "/appjs" -> "app.js"; "/style" -> "style.css"; else -> "index.html" }
                val type = when (r.path) { "/appjs" -> "text/javascript"; "/style" -> "text/css"; else -> "text/html; charset=utf-8" }
                LocalHttpsServer.reply(out, 200, type, context.assets.open("transfer/$asset").use { it.readBytes() }); return
            }
            if (r.path == "/pair" && r.method == "POST") {
                val value = body(r)
                synchronized(lock) {
                    if (now() - window > 60_000) { window = now(); attempts = 0 }
                    require(++attempts <= 5 && pairs.size < 10 && sessions.size < 10) { "Лимит попыток. Подождите минуту" }
                    // По решению владельца — без кодов и подтверждений: устройство в той же сети подключается сразу.
                    val name = value.optString("name").take(60).ifBlank { "Браузер" }
                    val token = id(); sessions[token] = now() + 3600_000
                    val key = id(); pairs[key] = Pairing(name, now() + 120_000, token)
                    mutable.value = mutable.value.copy(message = "Подключено: $name")
                    json(out, obj = JSONObject().put("id", key)); return
                }
            }
            if (r.path.startsWith("/pair/") && r.method == "GET") {
                val key = r.path.removePrefix("/pair/")
                val p = pairs[key]; require(p != null && p.expires > now()) { "Подключение отклонено или истекло" }
                json(out, obj = JSONObject().put("token", p.token ?: JSONObject.NULL)); return
            }
            if (r.path.startsWith("/download/") && r.method == "GET") {
                val ticket = tickets[r.path.removePrefix("/download/")] ?: error("Ссылка истекла")
                require(sessions[ticket.first]?.let { it > now() } == true)
                val item = shared[ticket.second] ?: error("Файл больше не доступен")
                require(item.size == item.file.length() && item.modified == item.file.lastModified()) { "Файл изменён; выберите его заново" }
                LocalHttpsServer.headers(out, 200, "application/octet-stream", item.size)
                java.nio.file.Files.newInputStream(item.file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.copyTo(out, 65536) }; return
            }
            val token = r.headers["authorization"]?.removePrefix("Bearer ") ?: error("Требуется подключение")
            require(sessions[token]?.let { it > now() } == true) { "Сеанс истёк" }
            when {
                r.path == "/files" && r.method == "GET" -> {
                    val array = JSONArray()
                    shared.forEach { (id, s) -> array.put(JSONObject().put("id", id).put("name", s.name).put("size", s.size).put("sha256", s.hash)) }
                    json(out, obj = JSONObject().put("files", array))
                }
                r.path.startsWith("/ticket/") && r.method == "POST" -> {
                    val file = r.path.removePrefix("/ticket/"); require(shared.containsKey(file))
                    require(tickets.size < 2000)
                    val ticket = id(); tickets[ticket] = token to file
                    json(out, obj = JSONObject().put("url", "/download/$ticket"))
                }
                r.path == "/batch" && r.method == "POST" -> {
                    require(batches.size < 100)
                    val batch = id(); batches[batch] = token
                    json(out, obj = JSONObject().put("id", batch))
                }
                r.path == "/offer" && r.method == "POST" -> {
                    val v = body(r); val name = v.getString("name"); IncomingTransfer.validatePath(name)
                    val size = v.getLong("size"); require(size >= 0 && size <= context.filesDir.usableSpace - 64L * 1024 * 1024)
                    val hash = v.optString("sha256"); require(hash.isEmpty() || hash.matches(Regex("[0-9a-f]{64}")))
                    synchronized(lock) {
                        require(uploads.size < 100 && uploads.values.count { it.owner == token && !it.done } < 8)
                        val batch = v.optString("batch").ifBlank { id().also { batches[it] = token } }
                        require(batches[batch] == token)
                        val key = id(); val upload = Upload(token, name, size, hash, batch); uploads[key] = upload
                        // Файлы принимаются сразу, без вопроса (решение владельца).
                        startReceiving(upload)
                        approvedBatches += batch
                        mutable.value = mutable.value.copy(message = "Приём: $name")
                        json(out, obj = JSONObject().put("id", key))
                    }
                }
                r.path.startsWith("/upload/") -> {
                    val key = r.path.removePrefix("/upload/"); val u = uploads[key] ?: error("Передача отменена")
                    require(u.owner == token)
                    val receiver = u.receiver
                    when (r.method) {
                        "GET" -> json(out, obj = JSONObject().put("accepted", receiver != null).put("offset", receiver?.offset() ?: 0).put("done", u.done))
                        "DELETE" -> { uploads.remove(key); receiver?.close(); update { it.copy(approvals = it.approvals.filterNot { a -> a.id == key }) }; json(out) }
                        "PUT" -> {
                            require(receiver != null && !u.done)
                            val offset = r.headers["x-offset"]?.toLong() ?: error("Нет смещения")
                            val hash = r.headers["x-sha256"].orEmpty() // страница браузера отправляет без суммы — быстрее
                            receiver.append(offset, r.length.toInt(), hash, r.body)
                            update { it.copy(progress = "${u.name}: ${receiver.offset()} / ${u.size}") }
                            json(out, obj = JSONObject().put("offset", receiver.offset()))
                        }
                        "POST" -> { require(receiver != null && !u.done); val saved = receiver.finish(); u.done = true
                            android.media.MediaScannerConnection.scanFile(context, arrayOf(saved.toString()), null, null)
                            update { it.copy(progress = "Получен: ${saved.fileName} → Загрузки/Передача") }; json(out) }
                        else -> error("Метод не поддерживается")
                    }
                }
                else -> json(out, 404, JSONObject().put("error", "Не найдено"))
            }
        } catch (e: Exception) {
            json(out, 400, JSONObject().put("error", e.message?.take(200) ?: "Ошибка передачи"))
        }
    }
}
