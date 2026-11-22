package com.example.meshcall

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.content.pm.PackageManager
import android.widget.*
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.URL
import androidx.core.app.ActivityCompat
import org.webrtc.*

class MainActivity : Activity() {
 private lateinit var status:TextView
 private lateinit var peersView:TextView
 private lateinit var countView:TextView
 private lateinit var name:EditText
 private var discovery:LanDiscovery?=null
 private var signal:SignalServer?=null
 private var mesh:WebRtcMesh?=null
 private val peers=mutableMapOf<String,LanDiscovery.Peer>()
 private val connectedAddresses=mutableSetOf<String>()
 private lateinit var remoteRenderer:SurfaceViewRenderer
 private lateinit var localRenderer:SurfaceViewRenderer
 private var egl:EglBase?=null
 private var renderersReady=false
 private var rtcInitializing=false
 private val localId=java.util.UUID.randomUUID().toString()
 private var mute=false
 private var cam=true
 private val uiHandler=android.os.Handler(android.os.Looper.getMainLooper())
 private val refreshPeers=object:Runnable { override fun run(){
  val current=discovery?.snapshot().orEmpty()
  peers.clear(); current.forEach{peers[it.id]=it}
  renderPeerList(); uiHandler.postDelayed(this,1500)
 }}

 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16)}
  box.addView(TextView(this).apply{text="MeshCall Offline\nLAN Audio / Video";textSize=22f})
  name=EditText(this).apply{hint="Your display name";setSingleLine(true)};box.addView(name)
  status=TextView(this).apply{text="Status: Ready";textSize=16f};box.addView(status)
  countView=TextView(this).apply{text="Nearby devices: 0 | Connected: 0";textSize=16f};box.addView(countView)
  peersView=TextView(this).apply{text="Nearby devices will appear here."};box.addView(peersView)
  remoteRenderer=SurfaceViewRenderer(this);localRenderer=SurfaceViewRenderer(this)
  box.addView(TextView(this).apply{text="Remote video"});box.addView(remoteRenderer,LinearLayout.LayoutParams(-1,300))
  box.addView(TextView(this).apply{text="Local preview"});box.addView(localRenderer,LinearLayout.LayoutParams(-1,180))
  box.addView(Button(this).apply{text="1. START SEARCHING DEVICES";setOnClickListener{startNetwork()}})
  box.addView(Button(this).apply{text="2. INITIALIZE AUDIO + CAMERA";setOnClickListener{if(!hasPermissions())requestPermissionsNow() else initRtc()}})
  box.addView(Button(this).apply{text="3. AUDIO CALL";setOnClickListener{startCall(videoCall=false)}})
  box.addView(Button(this).apply{text="4. VIDEO CALL";setOnClickListener{startCall(videoCall=true)}})
  box.addView(Button(this).apply{text="Mute / Unmute";setOnClickListener{mute=!mute;mesh?.setMuted(mute);status.text=if(mute)"Microphone muted" else "Microphone active"}})
  box.addView(Button(this).apply{text="Camera On / Off";setOnClickListener{cam=!cam;mesh?.setVideoEnabled(cam);status.text=if(cam)"Video enabled" else "Video disabled"}})
  box.addView(Button(this).apply{text="Check App Update";setOnClickListener{checkForUpdate(true)}})
  box.addView(Button(this).apply{text="STOP / DISCONNECT";setOnClickListener{stopAll()}})
  setContentView(ScrollView(this).apply{addView(box)})
  requestPermissionsNow();checkForUpdate(false)
 }

 private fun startCall(videoCall:Boolean){
  val m=mesh
  if(m==null){status.text="Please initialize Audio + Camera first";return}
  if(peers.isEmpty()){
   status.text="No devices found. Connect both phones to the same Wi-Fi/hotspot and press START SEARCHING DEVICES."
   return
  }
  // Audio call keeps microphone enabled and disables the outgoing video track.
  // Video call enables both microphone and camera video.
  cam=videoCall
  m.setMuted(false);mute=false
  m.setVideoEnabled(videoCall)
  peers.values.forEach{peer->m.call(peer.address)}
  status.text=if(videoCall) "Starting VIDEO CALL with ${peers.size} found device(s)…" else "Starting AUDIO CALL with ${peers.size} found device(s)…"
 }

 private fun renderPeerList(){
  val list=peers.values.sortedBy{it.name.lowercase()}
  countView.text="Nearby devices: ${list.size} | Connected: ${connectedAddresses.size}"
  peersView.text=if(list.isEmpty()) "No devices found yet.\n1) Connect both phones to the same Wi-Fi or one phone's hotspot.\n2) Enter a different display name on each phone.\n3) Press START SEARCHING DEVICES." else "FOUND DEVICES\n"+list.joinToString("\n\n") { p ->
   val connected=connectedAddresses.contains(p.address)
   "${p.name}\n${p.address}\n${if(connected)"🟢 CONNECTED" else "🟡 FOUND — not connected yet"}"
  }
 }
 private fun startNetwork(){
  val display=name.text.toString().trim().ifBlank{"Android-${android.os.Build.MODEL}"}
  signal?.stop();signal=SignalServer(onMessage={msg,addr->runOnUiThread{mesh?.receive(msg,addr)}}, onError={err->runOnUiThread{status.text=err}}).also{it.start()}
  discovery?.stop();discovery=LanDiscovery(this,display,localId){p->runOnUiThread{peers[p.id]=p;renderPeerList()}}.also{it.start()}
  status.text="Searching local network (Internet not required)… Keep both phones on the same hotspot/Wi-Fi."
  uiHandler.removeCallbacks(refreshPeers);uiHandler.post(refreshPeers)
 }
 private fun initRtc(){
  if(mesh!=null){status.text="Audio/Video already initialized. You can call devices now.";return}
  if(rtcInitializing)return
  rtcInitializing=true
  try{
   if(!renderersReady){egl=EglBase.create();remoteRenderer.init(egl!!.eglBaseContext,null);localRenderer.init(egl!!.eglBaseContext,null);remoteRenderer.setEnableHardwareScaler(true);localRenderer.setEnableHardwareScaler(true);renderersReady=true}
   if(signal==null) signal=SignalServer(onMessage={msg,addr->runOnUiThread{mesh?.receive(msg,addr)}}, onError={err->runOnUiThread{status.text=err}}).also{it.start()}
   mesh=WebRtcMesh(this,signal!!,localId,{s->runOnUiThread{
    status.text=s
    if(s.contains("ICE: CONNECTED")||s.contains("ICE: COMPLETED")){ val address=s.substringBefore(" ICE:");connectedAddresses.add(address);renderPeerList() }
    if(s.contains("DISCONNECTED")||s.contains("FAILED")){val address=s.substringBefore(" ICE:");connectedAddresses.remove(address);renderPeerList()}
   }},{t->runOnUiThread{t.addSink(remoteRenderer)}},{t->runOnUiThread{t.addSink(localRenderer)}},egl!!.eglBaseContext).also{it.initialize()}
   status.text="Audio/Video ready. Search devices, then call all found devices."
  }catch(e:Exception){status.text="RTC initialization failed: ${e.message}";mesh?.close();mesh=null}
  finally{rtcInitializing=false}
 }
 private fun stopAll(){discovery?.stop();discovery=null;signal?.stop();signal=null;mesh?.close();mesh=null;connectedAddresses.clear();peers.clear();uiHandler.removeCallbacks(refreshPeers);renderPeerList();status.text="Stopped. Press START SEARCHING DEVICES to begin again."}
 private fun hasPermissions()=ActivityCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED&&ActivityCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
 private fun requestPermissionsNow(){val a=arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA);val missing=a.filter{ActivityCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED};if(missing.isNotEmpty())ActivityCompat.requestPermissions(this,missing.toTypedArray(),10)}
 private fun checkForUpdate(showNoUpdate:Boolean){
  Thread {
   try {
    val url=URL("https://github.com/YubarajChaulagain/MeshCallOffline/releases/latest/download/latest.json")
    val conn=url.openConnection();conn.connectTimeout=7000;conn.readTimeout=7000
    val json=JSONObject(conn.getInputStream().bufferedReader().use{it.readText()})
    val latest=json.getInt("versionCode")
    val current=packageManager.getPackageInfo(packageName,0).longVersionCode.toInt()
    if(latest>current){runOnUiThread{
     AlertDialog.Builder(this).setTitle("New Update Available")
      .setMessage("Version ${json.optString("versionName")} is available. Current version: ${packageManager.getPackageInfo(packageName,0).versionName}")
      .setPositiveButton("Update Now"){_,_->startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(json.optString("downloadUrl"))))}
      .setNegativeButton("Later",null).show()
    }}else if(showNoUpdate){runOnUiThread{Toast.makeText(this,"App is up to date",Toast.LENGTH_SHORT).show()}}
   }catch(e:Exception){if(showNoUpdate)runOnUiThread{Toast.makeText(this,"Update check failed. Check internet.",Toast.LENGTH_SHORT).show()}}
  }.start()
 }

 override fun onDestroy(){stopAll();if(renderersReady){try{remoteRenderer.release();localRenderer.release();egl?.release()}catch(_:Exception){};renderersReady=false};super.onDestroy()}
}
