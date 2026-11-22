package com.example.meshcall

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simple LAN TCP signaling endpoint. The listening socket is bound before start() returns,
 * so callers cannot race the server startup.
 */
class SignalServer(
    private val port: Int = 45455,
    private val onMessage: (JSONObject, String) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    @Volatile private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val started = AtomicBoolean(false)

    @Synchronized
    fun start() {
        if (started.get()) return
        try {
            val listener = ServerSocket()
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(port))
            server = listener
            started.set(true)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope!!.launch {
                try {
                    while (isActive && !listener.isClosed) {
                        val client = listener.accept()
                        launch { readLoop(client) }
                    }
                } catch (e: Exception) {
                    if (isActive && !listener.isClosed) {
                        Log.e("MeshCallSignal", "Accept failed", e)
                        onError("Signaling listener failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            started.set(false)
            server = null
            onError("Cannot open local signaling port $port: ${e.message}")
        }
    }

    private fun readLoop(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            while (!socket.isClosed) {
                val line = reader.readLine() ?: break
                try {
                    onMessage(JSONObject(line), socket.inetAddress.hostAddress ?: "")
                } catch (e: Exception) {
                    Log.w("MeshCallSignal", "Invalid signaling message", e)
                }
            }
        } catch (e: Exception) {
            Log.w("MeshCallSignal", "Read failed: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    fun send(address: String, msg: JSONObject) {
        val currentScope = scope
        if (currentScope == null || !started.get()) {
            onError("Signaling server is not running")
            return
        }
        currentScope.launch {
            try {
                Socket().use { client ->
                    client.connect(InetSocketAddress(address, port), 3500)
                    client.soTimeout = 5000
                    PrintWriter(client.getOutputStream(), true).println(msg.toString())
                }
            } catch (e: Exception) {
                Log.e("MeshCallSignal", "Send to $address failed", e)
                onError("Cannot reach $address:$port — ${e.message}")
            }
        }
    }

    @Synchronized
    fun stop() {
        started.set(false)
        try { server?.close() } catch (_: Exception) { }
        server = null
        scope?.cancel()
        scope = null
    }
}
