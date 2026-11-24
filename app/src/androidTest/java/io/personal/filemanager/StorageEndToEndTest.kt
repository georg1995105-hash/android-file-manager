package io.personal.filemanager

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.personal.filemanager.data.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@RunWith(AndroidJUnit4::class)
class StorageEndToEndTest {
    @Test fun persistentPairingInterruptedUploadAndOfflineReceiver() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val cert = requireNotNull(args.getString("storageCert"))
        val code = requireNotNull(args.getString("storageCode"))
        val context = instrumentation.targetContext
        // Test-only trust anchor. Production client still uses Android's standard HTTPS validation.
        val factory = CertificateFactory.getInstance("X.509")
        val certificate = factory.generateCertificate(ByteArrayInputStream(VaultCrypto.unb64(cert)))
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture",certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val ssl = SSLContext.getInstance("TLS").apply { init(null,trust.trustManagers,null) }
        val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
        val dir = File(context.cacheDir,"imported-files/storage-e2e").apply { mkdirs() }
        val payload = File(dir,"fixture.apk")
        val content = ByteArray(VaultClient.CHUNK + 137) { (it % 251).toByte() }
        val store = VaultStore(context)
        try {
            payload.writeBytes(content)
            val phone = VaultPairing.bootstrap("https://localhost:8788",code,"Test phone")
            store.save(phone)
            val restored = requireNotNull(VaultStore(context).load())
            assertEquals(phone.token,restored.token)
            assertArrayEquals(phone.key,restored.key)
            val pair = VaultPairing.begin(restored.endpoint,"Test TV")
            assertNull(VaultPairing.claim(pair))
            VaultPairing.approve(restored,pair.code)
            val tv = requireNotNull(VaultPairing.claim(pair))
            assertArrayEquals(restored.key,tv.key)
            val sender = VaultFiles(context,restored)
            val uri = FileProvider.getUriForFile(context,"${context.packageName}.files",payload)
            val job = sender.enqueue(uri)
            try {
                sender.upload(job) { done,_ -> if (done >= VaultClient.CHUNK) throw IOException("simulated disconnection") }
                fail("upload should have been interrupted")
            } catch (_: IOException) {}
            assertEquals(1,VaultFiles(context,restored).pending().size)
            assertTrue(VaultFiles(context,tv).list().isEmpty())
            val restarted = VaultFiles(context,requireNotNull(store.load()))
            var firstOffset = -1L
            restarted.upload(restarted.pending().single()) { done,_ -> if (firstOffset == -1L) firstOffset=done }
            assertEquals(VaultClient.CHUNK.toLong(),firstOffset)
            assertTrue(restarted.pending().isEmpty())
            // TV has made no file-transfer request until after the phone finished uploading.
            val receiver = VaultFiles(context,tv)
            val remote = receiver.list().single()
            assertEquals("fixture.apk",remote.name)
            val destination = File(dir,"received").apply { mkdirs() }
            File(destination,"fixture.apk").writeText("do not overwrite")
            try {
                receiver.download(remote,destination) { done,_ -> if (done >= VaultClient.CHUNK) throw IOException("simulated receiver interruption") }
                fail("download should have been interrupted")
            } catch (_: IOException) {}
            var resumedDownload = -1L
            val received = VaultFiles(context,tv).download(remote,destination) { done,_ -> if (resumedDownload == -1L) resumedDownload=done }
            assertEquals(VaultClient.CHUNK.toLong(), resumedDownload)
            assertArrayEquals(content,received.readBytes())
            assertEquals("do not overwrite",File(destination,"fixture.apk").readText())
            restarted.revoke(restarted.devices().single { it.id==tv.device })
            assertTrue(runCatching { receiver.list() }.isFailure)
            restarted.delete(remote)
            assertTrue(restarted.list().isEmpty())
        } finally {
            store.clear()
            HttpsURLConnection.setDefaultSSLSocketFactory(previous)
            dir.deleteRecursively()
        }
    }
}
