package io.personal.filemanager.data.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Локальное хранение пароля автокопий: шифруется ключом Android Keystore (ключ не покидает устройство).
 * Это удобство только для автоматических копий на этом телефоне; для переноса на другой телефон
 * нужен сам пароль — Keystore его не заменяет (ТЗ §7).
 */
object SecretStore {
    private const val ALIAS = "pfm_auto_backup_password"
    private const val KEYSTORE = "AndroidKeyStore"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(secret: CharArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = String(secret).toByteArray(Charsets.UTF_8)
        val out = cipher.iv + cipher.doFinal(bytes)
        bytes.fill(0)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** null — ключ недоступен (например, после сброса защиты устройства) или данные повреждены. */
    fun decrypt(stored: String): CharArray? = runCatching {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        val plain = cipher.doFinal(data, 12, data.size - 12)
        val chars = String(plain, Charsets.UTF_8).toCharArray()
        plain.fill(0)
        chars
    }.getOrNull()
}
