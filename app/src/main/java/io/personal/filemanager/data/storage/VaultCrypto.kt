package io.personal.filemanager.data.storage

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Random 96-bit IV, 128-bit tag; each object and chunk has a distinct authenticated context. */
internal object VaultCrypto {
    private val random = SecureRandom()
    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun fromHex(value: String): ByteArray {
        require(value.length % 2 == 0 && value.matches(Regex("[0-9a-f]+"))) { "Неверный код" }
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value)
    fun seal(key: ByteArray, aad: String, plain: ByteArray): ByteArray =
        sealKey(SecretKeySpec(key, "AES"), aad, plain)
    fun open(key: ByteArray, aad: String, encrypted: ByteArray): ByteArray =
        openKey(SecretKeySpec(key, "AES"), aad, encrypted)
    fun sealKey(key: SecretKey, aad: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == 12)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return iv + cipher.doFinal(plain)
    }
    fun openKey(key: SecretKey, aad: String, encrypted: ByteArray): ByteArray {
        require(encrypted.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted, 12, encrypted.size - 12)
    }
}
