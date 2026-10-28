package io.personal.filemanager.data.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow

@Suppress("DEPRECATION")
class TransferDiscovery(context: Context) {
    data class Peer(val name: String, val address: String)
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    fun advertise(port: Int) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(NsdServiceInfo().apply { serviceName = "Мои файлы ${android.os.Build.MODEL}"; serviceType = "_pfmtransfer._tcp."; setPort(port) }, NsdManager.PROTOCOL_DNS_SD, listener) }
    }
    fun discover() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { discovery = null }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) { peers.value = peers.value.filterNot { it.name == info.serviceName } }
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        if (':' in host) return // Manual numeric IPv4 fallback is shared by all clients.
                        peers.value = (peers.value.filterNot { it.name == info.serviceName } + Peer(info.serviceName, "https://$host:${info.port}")).take(32)
                    }
                })
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices("_pfmtransfer._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
    }
    fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
        peers.value = emptyList()
    }
    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
        peers.value = emptyList()
    }
}
