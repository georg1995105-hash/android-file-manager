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
            val pairing = async { client.pair(state.code, "Integration fixture") }
            withTimeout(15000) { while (manager.state.value.approvals.isEmpty()) delay(100) }
            manager.approve(manager.state.value.approvals.first().id, true)
            pairing.await()
            val data = ByteArray(2 * 1024 * 1024 + 23) { (it % 251).toByte() }
            val file = File(fixtures, "fixture.apk").apply { writeBytes(data) }
            manager.share(listOf(file))
            withTimeout(15000) { while (manager.selectedFiles().isEmpty()) delay(100) }
            val sending = async { client.send(manager.selectedFiles().single()) {} }
            withTimeout(15000) { while (manager.state.value.approvals.isEmpty()) delay(100) }
            manager.approve(manager.state.value.approvals.first().id, true)
            sending.await()
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
}
