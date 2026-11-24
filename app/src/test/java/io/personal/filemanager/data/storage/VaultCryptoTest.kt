package io.personal.filemanager.data.storage

import org.junit.Assert.*
import org.junit.Test

class VaultCryptoTest {
    @Test fun encryptedChunksAndPairKeyCannotBeReorderedOrModified() {
        val key = VaultCrypto.randomBytes(32)
        val raw = ByteArray(4097) { (it % 251).toByte() }
        val a = VaultCrypto.seal(key, "object/0", raw)
        val b = VaultCrypto.seal(key, "object/0", raw)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(raw, VaultCrypto.open(key, "object/0", a))
        fun rejected(block: () -> Unit) {
            try { block(); fail("unauthenticated data accepted") } catch (_: javax.crypto.AEADBadTagException) {}
        }
        rejected { VaultCrypto.open(key, "object/1", a) }
        rejected { VaultCrypto.open(VaultCrypto.randomBytes(32), "object/0", a) }
        a[a.lastIndex] = (a.last().toInt() xor 1).toByte()
        rejected { VaultCrypto.open(key, "object/0", a) }
    }
    @Test fun rejectsUnsafeEndpoints() {
        assertEquals("https://storage.example.org", VaultClient.endpoint("https://storage.example.org/"))
        for (url in listOf("http://host", "https://user@host", "https://host/path", "https://host?q=x", "https://host/#x")) {
            assertTrue(runCatching { VaultClient.endpoint(url) }.isFailure)
        }
    }
}
