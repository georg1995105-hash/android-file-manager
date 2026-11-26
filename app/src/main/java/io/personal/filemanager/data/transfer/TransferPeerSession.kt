package io.personal.filemanager.data.transfer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import org.json.JSONObject

/** A single outgoing job belongs to the service, not to the lifetime of a screen. */
internal class TransferPeerSession {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var client by mutableStateOf<TransferClient?>(null)
    var connectedName by mutableStateOf("")
    var files by mutableStateOf<List<JSONObject>>(emptyList())
    var message by mutableStateOf("")
    var job by mutableStateOf<Job?>(null)
    var busy by mutableStateOf(false)

    fun cancel() {
        job?.cancel()
        client?.cancel()
        client = null
        files = emptyList()
        connectedName = ""
    }
}
