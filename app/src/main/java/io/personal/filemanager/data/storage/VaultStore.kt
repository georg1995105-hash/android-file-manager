package io.personal.filemanager.data.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class VaultSession(val endpoint: String, val token: String, val device: String, val key: ByteArray)
internal class VaultStore(context: Context) {
    private val prefs = context.getSharedPreferences("private-storage-v1", Context.MODE_PRIVATE)
    private val alias = "personal-storage-credentials-v1"
    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun load(): VaultSession? {
        val record = prefs.getString("session", null) ?: return null
        val raw = VaultCrypto.openKey(wrappingKey(), alias, VaultCrypto.unb64(record))
        val j = JSONObject(raw.toString(Charsets.UTF_8))
        return VaultSession(VaultClient.endpoint(j.getString("endpoint")), j.getString("token"), j.getString("device"),
            VaultCrypto.unb64(j.getString("key")).also { require(it.size == 32) })
    }
    fun save(s: VaultSession) {
        val raw = JSONObject().put("endpoint", s.endpoint).put("token", s.token).put("device", s.device)
            .put("key", VaultCrypto.b64(s.key)).toString().toByteArray()
        val sealed = VaultCrypto.b64(VaultCrypto.sealKey(wrappingKey(), alias, raw))
        check(prefs.edit().putString("session", sealed).commit()) { "Не удалось сохранить привязку" }
    }
    fun clear() { check(prefs.edit().remove("session").commit()) }
}
