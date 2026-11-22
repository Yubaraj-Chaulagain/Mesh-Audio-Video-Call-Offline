package com.example.meshcall

import org.json.JSONObject
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap

/** LAN-only TCP signaling transport. Each message is a JSON line. */
class SignalServer(private val port:Int=45455, private val onMessage:(JSONObject,String)->Unit) {
 private var server:ServerSocket?=null; private var scope:CoroutineScope?=null
 private val sockets=ConcurrentHashMap<String,Socket>()
 fun start(){ if(server!=null)return; scope=CoroutineScope(Dispatchers.IO); scope!!.launch { try { server=ServerSocket(port); while(isActive){ val s=server!!.accept(); launch { readLoop(s) } } }catch(_:Exception){} } }
 private suspend fun readLoop(s:Socket){ try { val r=BufferedReader(InputStreamReader(s.getInputStream())); while(true){ val line=r.readLine()?:break; try{onMessage(JSONObject(line),s.inetAddress.hostAddress?:"")}catch(_:Exception){} } }catch(_:Exception){}finally{try{s.close()}catch(_:Exception){}} }
 fun send(address:String,msg:JSONObject){ scope?.launch(Dispatchers.IO){ try{ Socket(address,port).use { PrintWriter(it.getOutputStream(),true).println(msg.toString()) } }catch(_:Exception){} } }
 fun stop(){scope?.cancel(); try{server?.close()}catch(_:Exception){};server=null}
}
