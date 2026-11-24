package io.personal.filemanager.data.drive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.personal.filemanager.BuildConfig
import org.json.JSONObject
import java.net.URL
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

internal data class TvCode(val device:String,val user:String,val url:String,val interval:Long,val expires:Long)
internal class TvDriveAuth(context:Context) {
    private val prefs=context.getSharedPreferences("pfm-drive-tv",Context.MODE_PRIVATE)
    private val alias="pfm-drive-tv-token"
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (ks.getKey(alias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun save(token:String) {
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key())
        check(prefs.edit().putString("refresh",Base64.getEncoder().encodeToString(c.iv+c.doFinal(token.toByteArray()))).commit())
    }
    private fun load():String? {
        val raw=prefs.getString("refresh",null) ?: return null
        val data=Base64.getDecoder().decode(raw);require(data.size>=28)
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,data.copyOfRange(0,12)))
        return c.doFinal(data,12,data.size-12).toString(Charsets.UTF_8)
    }
    fun disconnect(){prefs.edit().remove("refresh").apply()}
    private fun post(path:String,vararg params:Pair<String,String>):JSONObject {
        val c=URL("https://oauth2.googleapis.com/$path").openConnection() as HttpsURLConnection
        try {
            c.requestMethod="POST";c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=15000;c.doOutput=true
            c.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
            val body=params.joinToString("&"){DriveApi.encode(it.first)+"="+DriveApi.encode(it.second)}.toByteArray()
            c.setFixedLengthStreamingMode(body.size);c.outputStream.use{it.write(body)}
            val status=c.responseCode
            return (if(status in 200..299)c.inputStream else c.errorStream ?: error("Google: HTTP $status"))
                .bufferedReader().use{JSONObject(it.readText())}
        }finally{c.disconnect()}
    }
    fun begin():TvCode {
        val j=post("device/code","client_id" to BuildConfig.PFM_DRIVE_TV_CLIENT_ID,"scope" to DRIVE_SCOPE)
        if(j.has("error"))error("Google: "+j.getString("error"))
        val url=j.getString("verification_url")
        val u=java.net.URI(url);require(u.scheme=="https" && u.host in setOf("www.google.com","google.com","accounts.google.com") && u.userInfo==null)
        return TvCode(j.getString("device_code"),j.getString("user_code"),url,j.optLong("interval",5).coerceAtLeast(5),
            android.os.SystemClock.elapsedRealtime()+j.getLong("expires_in")*1000)
    }
    fun poll(code:TvCode):JSONObject=post("token","client_id" to BuildConfig.PFM_DRIVE_TV_CLIENT_ID,
        "client_secret" to BuildConfig.PFM_DRIVE_TV_CLIENT_SECRET,"device_code" to code.device,
        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code")
    fun accept(j:JSONObject):String {
        val token=j.getString("access_token")
        require(j.optString("scope").split(" ").contains(DRIVE_SCOPE)) {"Google Диск не разрешён"}
        save(j.getString("refresh_token"));return token
    }
    fun refresh():String? {
        val refresh=load() ?: return null
        val j=post("token","client_id" to BuildConfig.PFM_DRIVE_TV_CLIENT_ID,"client_secret" to BuildConfig.PFM_DRIVE_TV_CLIENT_SECRET,
            "refresh_token" to refresh,"grant_type" to "refresh_token")
        if(j.optString("error")=="invalid_grant"){disconnect();return null}
        if(j.has("error"))error("Google: "+j.getString("error"))
        return j.getString("access_token")
    }
}
