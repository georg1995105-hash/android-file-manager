package io.personal.filemanager.data.drive

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.personal.filemanager.data.FileSystemRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import javax.net.ssl.HttpsURLConnection

internal const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
internal data class DriveFile(val id:String,val name:String,val size:Long,val sha:String?)
internal class DriveApi(private val token:String) {
    companion object {
        const val CHUNK=4*1024*1024
        fun encode(s:String)=URLEncoder.encode(s,"UTF-8")
        fun validName(s:String):String {
            require(FileSystemRepository.validateName(s)==null && !s.startsWith(".") && !s.contains('\\')) {"Недопустимое имя файла"}
            return s
        }
        fun nextOffset(range:String?,size:Long):Long {
            if(range.isNullOrBlank()) return 0
            val end=Regex("bytes=0-([0-9]+)").matchEntire(range)?.groupValues?.get(1)?.toLongOrNull()
                ?: error("Некорректный ответ Google Диска")
            require(end in 0 until size)
            return end+1
        }
        fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
        fun allowedSession(url:String):Boolean=runCatching {
            val u=java.net.URI(url)
            u.scheme=="https" && u.host in setOf("www.googleapis.com","content.googleapis.com") &&
                u.userInfo==null && (u.port==-1 || u.port==443) && u.fragment==null && u.path.startsWith("/upload/drive/")
        }.getOrDefault(false)
    }
    private fun connection(url:String,method:String):HttpsURLConnection {
        val u=URL(url)
        require(u.protocol=="https" && u.host in setOf("www.googleapis.com","content.googleapis.com") && u.userInfo==null && (u.port==-1 || u.port==443))
        return (u.openConnection() as HttpsURLConnection).apply {
            requestMethod=method;connectTimeout=15000;readTimeout=60000
            instanceFollowRedirects=false;useCaches=false
            setRequestProperty("Authorization","Bearer $token")
            setRequestProperty("Accept-Encoding","identity")
        }
    }
    private fun read(c:HttpsURLConnection):JSONObject {
        val status=c.responseCode
        if(status !in 200..299) throw java.io.IOException("Google Диск: HTTP $status")
        return c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }
    fun account():String {
        val c=connection("https://www.googleapis.com/drive/v3/about?fields=user(displayName,emailAddress,permissionId)","GET")
        try { val u=read(c).getJSONObject("user");return u.optString("emailAddress").ifBlank { u.getString("permissionId") } }
        finally { c.disconnect() }
    }
    fun list():List<DriveFile> {
        val files=mutableListOf<DriveFile>()
        var page=""
        do {
            val query=encode("trashed = false and appProperties has { key='pfm' and value='transfer-v1' }")
            val url="https://www.googleapis.com/drive/v3/files?q=$query&fields=nextPageToken,files(id,name,size,sha256Checksum,ownedByMe)&pageSize=100&orderBy=createdTime%20desc"+
                if(page.isNotBlank()) "&pageToken="+encode(page) else ""
            val c=connection(url,"GET")
            try {
                val j=read(c);val a=j.getJSONArray("files")
                for(i in 0 until a.length()) {
                    val f=a.getJSONObject(i)
                    if(f.optBoolean("ownedByMe") && f.has("size")) files+=DriveFile(f.getString("id"),f.getString("name"),f.getLong("size"),f.optString("sha256Checksum").ifBlank{null})
                }
                page=j.optString("nextPageToken")
            } finally {c.disconnect()}
        } while(page.isNotBlank() && files.size<1000)
        return files
    }
    fun trash(file:DriveFile) {
        require(file.id.matches(Regex("[A-Za-z0-9_-]+")))
        val c=connection("https://www.googleapis.com/drive/v3/files/${file.id}?fields=id,trashed","POST")
        try {
            c.setRequestProperty("X-HTTP-Method-Override","PATCH")
            c.setRequestProperty("Content-Type","application/json; charset=UTF-8")
            val body=JSONObject().put("trashed",true).toString().toByteArray(Charsets.UTF_8)
            c.doOutput=true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val result=read(c)
            check(result.optString("id")==file.id && result.optBoolean("trashed")) { "Google Диск не подтвердил удаление" }
        } finally { c.disconnect() }
    }

    /** Seekable Android providers can be streamed directly. Pipes and unknown lengths still need a private snapshot. */
    private class UploadSource(val input:FileInputStream,val length:Long,val initialSize:Long,val temporary:File?):java.io.Closeable {
        private val channel:FileChannel = input.channel
        fun read(offset:Long,count:Int):ByteArray {
            val bytes=ByteArray(count)
            val buffer=ByteBuffer.wrap(bytes)
            while(buffer.hasRemaining()) {
                val n=channel.read(buffer,offset+buffer.position())
                if(n<=0)throw java.io.EOFException("Исходный файл изменился")
            }
            return bytes
        }
        suspend fun verify(c:HttpsURLConnection) {
            check(channel.size()==initialSize) { "Исходный файл изменился" }
            val result=c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            if(result.has("size"))check(result.getLong("size")==length) { "Google Диск: размер не совпал" }
            val expected=result.optString("sha256Checksum")
            if(expected.isNotBlank()) {
                val sha=MessageDigest.getInstance("SHA-256")
                var offset=0L
                while(offset<length) {
                    currentCoroutineContext().ensureActive()
                    val bytes=read(offset,minOf(CHUNK.toLong(),length-offset).toInt())
                    sha.update(bytes);offset+=bytes.size
                }
                check(hex(sha.digest()).equals(expected,true) && channel.size()==initialSize) { "Google Диск: исходный файл или контрольная сумма изменились" }
            }
        }
        override fun close() { try { input.close() } finally { temporary?.delete() } }
    }
    private suspend fun uploadSource(context:Context,uri:Uri):UploadSource {
        val resolver=context.contentResolver
        // Restrict direct mode to a whole seekable descriptor; never read outside a provider asset.
        val afd=runCatching { resolver.openAssetFileDescriptor(uri,"r") }.getOrNull()
        if(afd!=null) {
            var stream:FileInputStream?=null
            var retained=false
            try {
                val length=afd.length
                if(afd.startOffset==0L && length>=0L) {
                    val opened=afd.createInputStream()
                    stream=opened
                    val size=opened.channel.size()
                    if(size==length) {
                        opened.channel.position(0)
                        retained=true
                        return UploadSource(opened,length,size,null)
                    }
                }
            } catch(_:java.io.IOException) {
                // Pipes cannot seek; fall back to a disposable copy.
            } catch(_:UnsupportedOperationException) {
            }
            finally {
                if(!retained)runCatching { if(stream!=null)stream.close() else afd.close() }
            }
        }
        val temp=File.createTempFile("drive-upload-",".tmp",context.cacheDir)
        try {
            (resolver.openInputStream(uri) ?: error("Файл недоступен")).use { input ->
                temp.outputStream().use { out ->
                    val buf=ByteArray(256*1024)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val n=input.read(buf);if(n<0)break
                        require(temp.usableSpace>32*1024*1024) {"Недостаточно свободного места"}
                        out.write(buf,0,n)
                    }
                }
            }
            return UploadSource(FileInputStream(temp),temp.length(),temp.length(),temp)
        } catch(e:Exception) { temp.delete();throw e }
    }
    suspend fun upload(context:Context,uri:Uri,progress:(Long,Long)->Unit) {
        val resolver=context.contentResolver
        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null }?.let(::validName)
            ?: error("Не удалось определить имя файла")
        val mime=resolver.getType(uri)?.takeIf { it.matches(Regex("[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+")) } ?: "application/octet-stream"
        uploadSource(context,uri).use { source ->
            val size=source.length
            val meta=JSONObject().put("name",name).put("appProperties",JSONObject().put("pfm","transfer-v1"))
            val init=connection("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,size,sha256Checksum","POST")
            val session=try {
                val bytes=meta.toString().toByteArray()
                init.doOutput=true;init.setRequestProperty("Content-Type","application/json; charset=UTF-8")
                init.setRequestProperty("X-Upload-Content-Type",mime);init.setRequestProperty("X-Upload-Content-Length",size.toString())
                init.setFixedLengthStreamingMode(bytes.size);init.outputStream.use { it.write(bytes) }
                check(init.responseCode in 200..299) {"Google Диск: HTTP ${init.responseCode}"}
                requireNotNull(init.getHeaderField("Location")).also { require(allowedSession(it)) }
            }finally{init.disconnect()}
            run {
                var offset=0L
                var retries=0
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val count=minOf(CHUNK.toLong(),size-offset).toInt()
                    val chunk=source.read(offset,count)
                    val c=connection(session,"PUT")
                    var code=0
                    var range:String?=null
                    try {
                        c.doOutput=true;c.setRequestProperty("Content-Type",mime)
                        c.setRequestProperty("Content-Range",if(size==0L) "bytes */0" else "bytes $offset-${offset+count-1}/$size")
                        c.setFixedLengthStreamingMode(count);c.outputStream.use { it.write(chunk) }
                        code=c.responseCode;range=c.getHeaderField("Range")
                        if(code in 200..299) { source.verify(c);progress(size,size);return }
                    }catch(e:java.io.IOException) { if(++retries>3)throw e }
                    finally{c.disconnect()}
                    if(code==308) {
                        val next=nextOffset(range,size)
                        if(next<=offset) { if(++retries>3)error("Google Диск не подтвердил загрузку") } else retries=0
                        offset=next;progress(offset,size)
                    } else if(code==0 || code==429 || code>=500) {
                        if(++retries>3)error("Не удалось завершить загрузку")
                        kotlinx.coroutines.delay(1000L*retries)
                        val check=connection(session,"PUT")
                        try {
                            check.doOutput=true;check.setRequestProperty("Content-Range","bytes */$size");check.setFixedLengthStreamingMode(0)
                            check.outputStream.close()
                            when(val status=check.responseCode) {
                                in 200..299 -> {source.verify(check);progress(size,size);return}
                                308 -> {offset=nextOffset(check.getHeaderField("Range"),size);progress(offset,size)}
                                else -> error("Google Диск: HTTP $status")
                            }
                        }finally{check.disconnect()}
                    } else error("Google Диск: HTTP $code")
                }
            }
        }
    }
    suspend fun download(file:DriveFile,directory:File,progress:(Long,Long)->Unit):File {
        val name=validName(file.name)
        require(file.id.matches(Regex("[A-Za-z0-9_-]+")) && file.size>=0)
        check(directory.isDirectory || directory.mkdirs()) {"Папка недоступна"}
        require(directory.usableSpace>file.size+16*1024*1024) {"Недостаточно места"}
        val temp=File.createTempFile(".drive-",".part",directory)
        try {
            val sha=MessageDigest.getInstance("SHA-256")
            var done=0L
            var failures=0
            temp.outputStream().use { out ->
                val buf=ByteArray(256*1024)
                do {
                    currentCoroutineContext().ensureActive()
                    val c=connection("https://www.googleapis.com/drive/v3/files/${file.id}?alt=media","GET")
                    try {
                        if(done>0) c.setRequestProperty("Range","bytes=$done-")
                        val status=c.responseCode
                        if(status==429 || status>=500) throw java.io.IOException("Google Диск: HTTP $status")
                        check(if(done==0L) status==200 else status==206 &&
                            c.getHeaderField("Content-Range")=="bytes $done-${file.size-1}/${file.size}") { "Google Диск: HTTP $status, неверный диапазон" }
                        c.inputStream.use { input ->
                            while(done<file.size) {
                                currentCoroutineContext().ensureActive()
                                val n=input.read(buf,0,minOf(buf.size.toLong(),file.size-done).toInt())
                                if(n<0) throw java.io.EOFException("Соединение оборвалось")
                                if(n==0) continue
                                try { out.write(buf,0,n) } catch(e:java.io.IOException) { throw IllegalStateException("Не удалось записать файл",e) }
                                sha.update(buf,0,n);done+=n;failures=0;progress(done,file.size)
                            }
                            check(input.read()==-1) { "Некорректный размер" }
                        }
                    } catch(e:java.io.IOException) {
                        currentCoroutineContext().ensureActive()
                        if(done==file.size || ++failures>5) throw e
                        kotlinx.coroutines.delay(1000L*failures)
                    } finally { c.disconnect() }
                } while(done<file.size)
                out.fd.sync()
            }
            check(done==file.size) { "Файл скачан не полностью" }
            file.sha?.let { check(hex(sha.digest()).equals(it,true)) { "Контрольная сумма не совпала" } }
            val dot=name.lastIndexOf('.').takeIf { it>0 } ?: name.length
            for(i in 0..9999) {
                val target=File(directory,if(i==0) name else name.substring(0,dot)+" ($i)"+name.substring(dot))
                try { Files.move(temp.toPath(),target.toPath());return target } catch(_:FileAlreadyExistsException){}
            }
            error("Нет свободного имени файла")
        }finally{temp.delete()}
    }
}
