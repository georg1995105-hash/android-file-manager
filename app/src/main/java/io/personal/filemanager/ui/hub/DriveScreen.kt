package io.personal.filemanager.ui.hub

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import io.personal.filemanager.BuildConfig
import io.personal.filemanager.core.formatSize
import io.personal.filemanager.data.FileEntry
import io.personal.filemanager.data.drive.*
import io.personal.filemanager.ui.*
import io.personal.filemanager.ui.components.ScreenTopBar
import kotlinx.coroutines.*

@Composable
internal fun DriveScreen(shared:List<Uri>,onSharedConsumed:()->Unit) {
    val context=LocalContext.current
    val nav=LocalNavigator.current
    val container=appContainer()
    val tv=isTelevisionUi()
    val scope=rememberCoroutineScope()
    val auth=remember{TvDriveAuth(context.applicationContext)}
    val prefs=remember{context.getSharedPreferences("pfm-drive-ui",0)}
    val enabled=BuildConfig.PFM_DRIVE_ENABLED && (!tv || (BuildConfig.PFM_DRIVE_TV_CLIENT_ID.isNotBlank() && BuildConfig.PFM_DRIVE_TV_CLIENT_SECRET.isNotBlank()))
    var token by remember{mutableStateOf<String?>(null)}
    var account by remember{mutableStateOf("")}
    var files by remember{mutableStateOf<List<DriveFile>>(emptyList())}
    var busy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf("")}
    var progress by remember{mutableStateOf<Float?>(null)}
    var code by remember{mutableStateOf<TvCode?>(null)}
    var job by remember{mutableStateOf<Job?>(null)}
    var shareDialog by remember(shared){mutableStateOf(shared.isNotEmpty())}
    fun task(label:String,block:suspend()->Unit) {
        if(busy)return
        busy=true;message=label;progress=null
        job=scope.launch {
            try{block()}catch(e:CancellationException){message="Остановлено";throw e}
            catch(e:Exception){message=e.message?.take(180)?:"Ошибка Google Диска"}
            finally{busy=false;progress=null;code=null}
        }
    }
    suspend fun refresh(t:String) {
        val result=withContext(Dispatchers.IO){val api=DriveApi(t);api.account() to api.list()}
        account=result.first;files=result.second;token=t;message=""
        prefs.edit().putBoolean("connected",true).apply()
    }
    fun report(done:Long,total:Long) { progress=if(total>0)(done.toDouble()/total).toFloat().coerceIn(0f,1f) else 1f;message=formatSize(done)+" / "+formatSize(total) }
    val client=remember{if(!tv && enabled)runCatching{Identity.getAuthorizationClient(context)}.getOrNull() else null}
    val consent=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result->
        if(result.resultCode==Activity.RESULT_OK) {
            runCatching{client?.getAuthorizationResultFromIntent(result.data)?.accessToken}.onSuccess{t->if(t!=null)task("Подключение…"){refresh(t)}}
                .onFailure{message="Не удалось подключить Google Диск"}
        }else message="Подключение отменено"
    }
    fun connectPhone(interactive:Boolean) {
        val c=client ?: run{message="Сервисы Google недоступны";return}
        val request=AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_SCOPE))).build()
        c.authorize(request).addOnSuccessListener { result->
            if(result.hasResolution()) {
                if(interactive) result.pendingIntent?.let{consent.launch(IntentSenderRequest.Builder(it).build())}
                else message="Подключите Google Диск"
            }else result.accessToken?.let{t->task("Подключение…"){refresh(t)}}
        }.addOnFailureListener{message="Google Диск недоступен: "+(it.message?:"ошибка авторизации").take(120)}
    }
    fun connectTv()=task("Подключение…") {
        val grant=withContext(Dispatchers.IO){auth.begin()}
        code=grant;message="Подтвердите доступ на телефоне"
        var interval=grant.interval
        while(android.os.SystemClock.elapsedRealtime()<grant.expires) {
            delay(interval*1000)
            val result=withContext(Dispatchers.IO){auth.poll(grant)}
            when(val error=result.optString("error")) {
                ""->{val t=withContext(Dispatchers.IO){auth.accept(result)};refresh(t);return@task}
                "authorization_pending"->Unit
                "slow_down"->interval+=5
                else->error("Google: $error")
            }
        }
        message="Код истёк. Подключитесь снова."
    }
    fun upload(uris:List<Uri>) {
        val t=token?:return
        task("Подготовка…") {
            withContext(Dispatchers.IO){uris.take(100).forEach{DriveApi(t).upload(context,it,::report)}}
            refresh(t);message="Отправлено в Google Диск"
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){upload(it)}
    LaunchedEffect(enabled) {
        if(enabled) {
            if(tv)task("Подключение…"){withContext(Dispatchers.IO){auth.refresh()}?.let{refresh(it)}?:run{message=""}}
            else if(prefs.getBoolean("connected",false))connectPhone(false)
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title="Личное облако",onBack={nav.back()})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Google Диск",style=MaterialTheme.typography.titleLarge)
            Text("Ваши файлы хранятся в вашем аккаунте Google. Подключите один и тот же аккаунт на своих устройствах.")
            if(!enabled) {
                Text("Облачная передача пока не настроена в этой сборке. Передача по Wi-Fi работает без облака.")
                Button(onClick={onSharedConsumed();nav.openSection(io.personal.filemanager.core.SectionId.TRANSFER)}){Text("Передача по Wi-Fi")}
            }else if(token==null) {
                if(!busy)Button(onClick={if(tv)connectTv()else connectPhone(true)}){Text("Подключить Google Диск")}
                code?.let{grant->
                    Text("Откройте адрес на телефоне и введите код",style=MaterialTheme.typography.titleMedium)
                    val qr=remember(grant.url){val m=MultiFormatWriter().encode(grant.url,BarcodeFormat.QR_CODE,256,256)
                        Bitmap.createBitmap(IntArray(256*256){i->if(m[i%256,i/256])android.graphics.Color.BLACK else android.graphics.Color.WHITE},256,256,Bitmap.Config.ARGB_8888).asImageBitmap()}
                    Image(qr,"QR входа Google",modifier=Modifier.size(200.dp))
                    Text(grant.url);Text(grant.user,style=MaterialTheme.typography.headlineMedium)
                }
            }else {
                Text(account,style=MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(!tv)Button(enabled=!busy,onClick={picker.launch(arrayOf("*/*"))}){Text("Отправить файл")}
                    OutlinedButton(enabled=!busy,onClick={if(tv)task("Обновление…"){val t=withContext(Dispatchers.IO){auth.refresh()}?:error("Подключите аккаунт");refresh(t)}else connectPhone(true)}){Text("Обновить")}
                }
                Text("Файлы · ${files.size}",style=MaterialTheme.typography.titleMedium)
                if(files.isEmpty())Text("Здесь будут файлы, отправленные с ваших устройств.")
                files.forEach{file->
                    OutlinedButton(enabled=!busy,modifier=Modifier.fillMaxWidth(),onClick={task("Скачивание…"){
                        val t=token?:error("Подключите аккаунт")
                        val f=withContext(Dispatchers.IO){DriveApi(t).download(file,container.transfer.receivedDirectory,::report)}
                        container.transfer.rescan(f.absolutePath);message="Получено";nav.openFile(FileEntry.from(f))
                    }}){Column(Modifier.fillMaxWidth()){Text(file.name);Text(formatSize(file.size),style=MaterialTheme.typography.bodySmall)}}
                }
                TextButton(enabled=!busy,onClick={auth.disconnect();prefs.edit().remove("connected").apply();token=null;account="";files=emptyList();message="Аккаунт отключён на этом устройстве"}){Text("Отключить")}
            }
            if(busy){progress?.let{LinearProgressIndicator(progress={it},modifier=Modifier.fillMaxWidth())}?:LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick={job?.cancel()}){Text("Остановить")}}
            if(message.isNotBlank())Text(message)
        }
    }
    if(shareDialog && shared.isNotEmpty() && token!=null && !busy) AlertDialog(onDismissRequest={shareDialog=false;onSharedConsumed()},
        title={Text("Отправить в Google Диск?")},text={Text("Файлов: ${shared.size}")},
        confirmButton={TextButton(onClick={shareDialog=false;upload(shared);onSharedConsumed()}){Text("Отправить")}},
        dismissButton={TextButton(onClick={shareDialog=false;onSharedConsumed()}){Text("Отмена")}})
}
