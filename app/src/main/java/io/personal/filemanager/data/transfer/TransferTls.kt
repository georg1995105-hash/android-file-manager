package io.personal.filemanager.data.transfer

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/** A transport identity, unrelated to APK signing. Private key never leaves Android Keystore. */
internal object TransferTls {
    fun context(): Pair<SSLContext, String> {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // v2: TLS signs raw digests (NONEwithRSA / RSA NoPadding for PSS), which v1 did not authorize.
        val alias = "local-transfer-tls-v2"
        if (store.containsAlias("local-transfer-tls-v1")) store.deleteEntry("local-transfer-tls-v1")
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=Local File Transfer"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(0))
                    .setCertificateNotAfter(Date(4102444800000L)).build())
                generateKeyPair()
            }
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, null) }
        val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).encoded)
            .joinToString(":") { "%02X".format(it) }
        return ssl to fingerprint
    }
}
