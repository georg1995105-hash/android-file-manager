package io.personal.filemanager.data.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.personal.filemanager.data.FileSystemRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Stage only explicitly selected content URIs; never modify the original provider files. */
internal object LocalShareFiles {
    // Only this app's disposable cache; never remove user-selected originals.
    private var cleaned = false

    // Called on I/O by the first staging/upload request, never on the application startup thread.
    @Synchronized
    fun cleanAfterProcessRestart(context: Context) {
        if (cleaned) return
        File(context.cacheDir, "transfer-selected").deleteRecursively()
        context.cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("drive-upload-") && it.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
        cleaned = true
    }
    suspend fun stage(context:Context,uris:List<Uri>):List<File> {
        require(uris.size in 1..100)
        cleanAfterProcessRestart(context)
        val root=File(context.cacheDir,"transfer-selected").apply { check(isDirectory || mkdirs()) }
        val batch=File(root,java.util.UUID.randomUUID().toString()).apply { check(mkdir()) }
        try {
            return uris.mapIndexed { index,uri ->
                currentCoroutineContext().ensureActive()
                require(uri.scheme=="content")
                val resolver=context.contentResolver
                val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}
                    ?.takeIf{FileSystemRepository.validateName(it)==null && !it.startsWith(".") && !it.contains('\\')}
                    ?: error("Не удалось определить безопасное имя файла")
                val dir=File(batch,index.toString()).apply{check(mkdir())}
                val file=File(dir,name)
                (resolver.openInputStream(uri)?:error("Источник недоступен")).use{input->
                    file.outputStream().use{out->
                        val buf=ByteArray(256*1024)
                        while(true){currentCoroutineContext().ensureActive();val n=input.read(buf);if(n<0)break
                            require(batch.usableSpace>32*1024*1024){"Недостаточно свободного места"};out.write(buf,0,n)}
                        out.fd.sync()
                    }
                }
                file
            }
        }catch(e:Exception){batch.deleteRecursively();throw e}
    }
}
