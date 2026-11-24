package io.personal.filemanager.data.storage

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class VaultClient private constructor(private val base: String, private val token: String?) {
    constructor(session: VaultSession) : this(session.endpoint, session.token)
    constructor(endpoint: String) : this(endpoint(endpoint), null)
    companion object {
        const val CHUNK = 4 * 1024 * 1024
        const val MAX_FILE = 10L * 1024 * 1024 * 1024
        fun endpoint(value: String): String {
            val u = URI(value.trim())
            require(u.scheme == "https" && !u.host.isNullOrBlank() && u.rawUserInfo == null &&
                u.rawQuery == null && u.rawFragment == null && (u.rawPath.isNullOrEmpty() || u.rawPath == "/") &&
                (u.port == -1 || u.port in 1..65535)) { "Нужен HTTPS-адрес хранилища без пути" }
            return u.toASCIIString().trimEnd('/')
        }
    }
    fun json(method: String, path: String, body: JSONObject? = null): JSONObject =
        JSONObject(request(method, path, body?.toString()?.toByteArray(), "application/json", 12 * 1024 * 1024).toString(Charsets.UTF_8))
    fun put(path: String, data: ByteArray): JSONObject =
        JSONObject(request("PUT", path, data, "application/octet-stream", 65536).toString(Charsets.UTF_8))
    fun get(path: String, max: Int): ByteArray = request("GET", path, null, "application/octet-stream", max)
    private fun request(method: String, path: String, body: ByteArray?, type: String, limit: Int): ByteArray {
        require(path.matches(Regex("/v1/[a-z0-9/]+")))
        val conn = URL(base + path).openConnection() as HttpsURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 12000
            conn.readTimeout = 35000
            conn.requestMethod = method
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            if (status !in 200..299) {
                val error = conn.errorStream?.use { readBounded(it, 8192) }?.toString(Charsets.UTF_8)
                val message = runCatching { JSONObject(error.orEmpty()).optString("error") }.getOrNull()
                error("Хранилище: HTTP $status" + if (message.isNullOrBlank()) "" else " · $message")
            }
            return conn.inputStream.use { readBounded(it, limit) }
        } finally { conn.disconnect() }
    }
    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32768)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= limit) { "Слишком большой ответ хранилища" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
internal class VaultPair(val endpoint: String, val id: String, val secret: ByteArray) {
    val code: String get() = id + "." + VaultCrypto.hex(secret)
    val proof: String get() = VaultCrypto.hash(secret)
}
internal object VaultPairing {
    private fun j(vararg fields: Pair<String, Any>): JSONObject = JSONObject().apply { fields.forEach { (k,v) -> put(k,v) } }
    fun bootstrap(endpoint: String, code: String, name: String): VaultSession {
        val base = VaultClient.endpoint(endpoint)
        require(code.matches(Regex("[0-9a-f]{64}"))) { "Неверный код первоначальной настройки" }
        val key = VaultCrypto.randomBytes(32)
        val response = VaultClient(base).json("POST", "/v1/bootstrap", j("code" to code, "name" to name))
        return VaultSession(base, response.getString("token"), response.getString("device"), key)
    }
    fun begin(endpoint: String, name: String): VaultPair {
        val base = VaultClient.endpoint(endpoint)
        val secret = VaultCrypto.randomBytes(32)
        val response = VaultClient(base).json("POST", "/v1/pair", j("proof" to VaultCrypto.hash(secret), "name" to name))
        return VaultPair(base, response.getString("id"), secret)
    }
    fun approve(session: VaultSession, code: String) {
        val raw = code.trim()
        require(raw.matches(Regex("[0-9a-f]{32}\\.[0-9a-f]{64}"))) { "Неверный код привязки" }
        val pair = VaultPair(session.endpoint, raw.substringBefore('.'), VaultCrypto.fromHex(raw.substringAfter('.')))
        val wrapped = VaultCrypto.seal(pair.secret, "pfm-pair-v1/" + pair.id, session.key)
        VaultClient(session).json("POST", "/v1/pair/" + pair.id + "/approve",
            j("proof" to pair.proof, "wrapped" to VaultCrypto.b64(wrapped)))
    }
    fun claim(pair: VaultPair): VaultSession? {
        val response = VaultClient(pair.endpoint).json("POST", "/v1/pair/" + pair.id + "/claim", j("proof" to pair.proof))
        if (response.optBoolean("pending")) return null
        val key = VaultCrypto.open(pair.secret, "pfm-pair-v1/" + pair.id, VaultCrypto.unb64(response.getString("wrapped")))
        require(key.size == 32)
        return VaultSession(pair.endpoint, response.getString("token"), response.getString("device"), key)
    }
}
