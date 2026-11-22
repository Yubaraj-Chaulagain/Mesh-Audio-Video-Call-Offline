package com.example.meshcall

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * LAN discovery for Wi-Fi and phone hotspots.
 * Sends to both limited broadcast and each active interface's directed broadcast.
 */
class LanDiscovery(
    private val context: Context,
    private val displayName: String,
    private val deviceId: String = UUID.randomUUID().toString(),
    private val onPeer: (Peer) -> Unit
) {
    data class Peer(val id: String, val name: String, val address: String, val lastSeen: Long)

    private val peers = ConcurrentHashMap<String, Peer>()
    private var job: Job? = null
    @Volatile private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val port = 45454
    private val id = deviceId

    fun start() {
        if (job?.isActive == true) return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            lock = wm.createMulticastLock("MeshCallDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { }

        job = CoroutineScope(Dispatchers.IO).launch {
            var activeSocket: DatagramSocket? = null
            try {
                activeSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(port))
                }
                socket = activeSocket

                val sender = launch {
                    while (isActive && activeSocket.isBound && !activeSocket.isClosed) {
                        val message = "MESH2|$id|${displayName.take(48)}".toByteArray(Charsets.UTF_8)
                        val destinations = linkedSetOf<InetAddress>()
                        try { destinations.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) { }
                        try {
                            val interfaces = NetworkInterface.getNetworkInterfaces()
                            while (interfaces.hasMoreElements()) {
                                val networkInterface = interfaces.nextElement()
                                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                                networkInterface.interfaceAddresses.forEach { address ->
                                    address.broadcast?.let { destinations.add(it) }
                                }
                            }
                        } catch (_: Exception) { }
                        destinations.forEach { destination ->
                            try {
                                activeSocket.send(DatagramPacket(message, message.size, destination, port))
                            } catch (_: Exception) { }
                        }
                        delay(1800)
                    }
                }

                val buffer = ByteArray(2048)
                while (isActive && !activeSocket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    activeSocket.receive(packet)
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val parts = text.split('|', limit = 3)
                    if (parts.size == 3 && (parts[0] == "MESH2" || parts[0] == "MESH1") && parts[1] != id) {
                        val address = packet.address.hostAddress ?: continue
                        if (address == "127.0.0.1" || address == "0.0.0.0") continue
                        val peer = Peer(parts[1], parts[2].ifBlank { "Unknown device" }, address, System.currentTimeMillis())
                        peers[peer.id] = peer
                        onPeer(peer)
                    }
                }
                sender.cancel()
            } catch (e: Exception) {
                if (isActive) android.util.Log.e("MeshCallDiscovery", "Discovery socket failed", e)
            } finally {
                try { activeSocket?.close() } catch (_: Exception) { }
                if (socket === activeSocket) socket = null
            }
        }
    }

    fun snapshot(): List<Peer> {
        val now = System.currentTimeMillis()
        peers.entries.removeIf { now - it.value.lastSeen > 10000 }
        return peers.values.sortedBy { it.name.lowercase() }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        try { lock?.release() } catch (_: Exception) { }
        lock = null
        peers.clear()
    }
}
