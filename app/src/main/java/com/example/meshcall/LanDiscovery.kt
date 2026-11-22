package com.example.meshcall

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** UDP broadcast peer discovery for devices on the same LAN/hotspot. */
class LanDiscovery(private val context: Context, private val displayName: String, private val onPeer: (Peer) -> Unit) {
    data class Peer(val id: String, val name: String, val address: String, val lastSeen: Long)
    private val peers = ConcurrentHashMap<String, Peer>()
    private var job: Job? = null
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val port = 45454
    private val id = "${android.os.Build.MODEL}-${android.os.Build.VERSION.SDK_INT}-${System.identityHashCode(this)}"

    fun start() {
        if (job != null) return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wm.createMulticastLock("MeshCallDiscovery").apply { setReferenceCounted(false); acquire() }
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(port)); broadcast = true }
                val s = socket!!
                launch {
                    while (isActive) {
                        val msg = "MESH1|$id|$displayName".toByteArray()
                        s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), port))
                        delay(2500)
                    }
                }
                val buf = ByteArray(2048)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size); s.receive(packet)
                    val parts = String(packet.data, 0, packet.length).split('|', limit=3)
                    if (parts.size == 3 && parts[0] == "MESH1" && parts[1] != id) {
                        val peer = Peer(parts[1], parts[2], packet.address.hostAddress ?: "", System.currentTimeMillis())
                        peers[peer.id] = peer; onPeer(peer)
                    }
                }
            } catch (_: Exception) { }
        }
    }
    fun snapshot(): List<Peer> = peers.values.sortedBy { it.name }
    fun stop() { job?.cancel(); job = null; try { socket?.close() } catch (_: Exception) {}; socket = null; try { lock?.release() } catch (_: Exception) {}; lock = null }
}
