package io.personal.filemanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.personal.filemanager.data.transfer.TransferClient
import io.personal.filemanager.data.transfer.TransferManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class TransferIntegrationTest {
    @Test fun tlsPairingAndTwoWayTransfer() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = TransferManager(context)
        val fixtures = File(context.cacheDir, "transfer-integration-${System.nanoTime()}").apply { mkdirs() }
        val original = manager.receivedDirectory.listFiles()?.toSet().orEmpty()
        try {
            manager.start()
            withTimeout(15000) { while (!manager.state.value.running) { delay(100); if (manager.state.value.message.startsWith("Не удалось")) error(manager.state.value.message) } }
            val state = manager.state.value
            val address = state.addresses.first()
            val pin = TransferClient(address).inspect()
            assertEquals(state.fingerprint, pin)
            try { TransferClient(address, "wrong").api("/files"); fail("Wrong pin accepted") } catch (_: Exception) { }
            val client = TransferClient(address, pin)
            try { client.api("/files"); fail("Unauthenticated list accepted") } catch (_: Exception) { }
            // Подключение без подтверждения (решение владельца): токен выдаётся сразу.
            withTimeout(15000) { client.pair("", "Integration fixture") }
            val data = ByteArray(2 * 1024 * 1024 + 23) { (it % 251).toByte() }
            val file = File(fixtures, "fixture.apk").apply { writeBytes(data) }
            manager.share(listOf(file))
            withTimeout(15000) { while (manager.selectedFiles().isEmpty()) delay(100) }
            withTimeout(60000) { client.send(manager.selectedFiles().single()) {} }
            val remote = client.api("/files").getJSONArray("files").getJSONObject(0)
            client.receive(remote, manager) {}
            val results = manager.receivedDirectory.listFiles()!!.filter { it !in original }.flatMap { it.walkTopDown().filter(File::isFile).toList() }
            assertEquals(2, results.size)
            results.forEach { assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(data), MessageDigest.getInstance("SHA-256").digest(it.readBytes())) }
        } finally {
            manager.stop()
            fixtures.deleteRecursively()
            manager.receivedDirectory.listFiles()?.filter { it !in original }?.forEach { it.deleteRecursively() }
        }
    }

    /** Exercise the exact HTTP endpoint encoded into the QR, not the separate TLS app transport. */
    @Test fun qrBrowserUploadSavesExactBytes() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = TransferManager(context)
        val name = "qr-tv-test-${System.nanoTime()}.bin"
        val expected = ByteArray(128 * 1024 + 17) { (it % 251).toByte() }
        try {
            manager.start()
            withTimeout(15000) { while (!manager.state.value.running) {
                delay(100)
                check(!manager.state.value.message.startsWith("Не удалось")) { manager.state.value.message }
            } }
            val address = java.net.URI(manager.state.value.browserAddresses.first())
            fun request(method: String, path: String, body: ByteArray = byteArrayOf(),
                        headers: Map<String, String> = emptyMap()): String {
                // Raw socket matches browser HTTP while keeping Android's app cleartext policy unchanged.
                return java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(address.host, address.port), 5000)
                    socket.soTimeout = 15000
                    val head = buildString {
                        append("$method $path HTTP/1.1\r\nHost: ${address.host}:${address.port}\r\n")
                        append("Content-Length: ${body.size}\r\nConnection: close\r\n")
                        headers.forEach { (key, value) -> append("$key: $value\r\n") }
                        append("\r\n")
                    }
                    socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(body); flush() }
                    val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                    assertTrue(response.take(300), response.startsWith("HTTP/1.1 200 "))
                    response.substringAfter("\r\n\r\n")
                }
            }
            assertTrue(request("GET", "/").contains("Выбрать файлы"))
            assertTrue(request("GET", "/appjs").isNotBlank())
            val pair = org.json.JSONObject(request("POST", "/pair", """{"name":"QR browser test"}""".toByteArray())).getString("id")
            val token = org.json.JSONObject(request("GET", "/pair/$pair")).getString("token")
            val auth = mapOf("Authorization" to "Bearer $token")
            val batch = org.json.JSONObject(request("POST", "/batch", headers = auth)).getString("id")
            val offer = org.json.JSONObject().put("name", name).put("size", expected.size).put("batch", batch)
            val id = org.json.JSONObject(request("POST", "/offer", offer.toString().toByteArray(), auth)).getString("id")
            val uploaded = org.json.JSONObject(request("PUT", "/upload/$id", expected, auth + ("X-Offset" to "0")))
            assertEquals(expected.size.toLong(), uploaded.getLong("offset"))
            request("POST", "/upload/$id", headers = auth)
            // Retrying a lost completion acknowledgement must not publish a duplicate.
            request("POST", "/upload/$id", headers = auth)
            assertTrue(org.json.JSONObject(request("GET", "/upload/$id", headers = auth)).getBoolean("done"))
            assertArrayEquals(expected, File(manager.receivedDirectory, name).readBytes())
            assertFalse(File(manager.receivedDirectory,name.removeSuffix(".bin")+" (1).bin").exists())
        } finally {
            manager.stop()
            File(manager.receivedDirectory, name).delete()
        }
    }
}
