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
 private lateinit var status:TextView; private lateinit var peersView:TextView; private lateinit var name:EditText
 private var discovery:LanDiscovery?=null; private var signal:SignalServer?=null; private var mesh:WebRtcMesh?=null
 private val peers=mutableMapOf<String,LanDiscovery.Peer>(); private lateinit var remoteRenderer:SurfaceViewRenderer; private lateinit var localRenderer:SurfaceViewRenderer; private lateinit var egl:EglBase
 private val localId="${android.os.Build.MODEL}-${System.currentTimeMillis()}"
 private var mute=false; private var cam=true
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(12,12,12,12)}
 box.addView(TextView(this).apply{text="MeshCall Offline\nLAN Audio / Video";textSize=22f});name=EditText(this).apply{hint="Your display name"};box.addView(name);status=TextView(this).apply{text="Ready"};box.addView(status);peersView=TextView(this);box.addView(peersView)
 remoteRenderer=SurfaceViewRenderer(this);localRenderer=SurfaceViewRenderer(this);box.addView(TextView(this).apply{text="Remote video"});box.addView(remoteRenderer,LinearLayout.LayoutParams(-1,360));box.addView(TextView(this).apply{text="Local preview"});box.addView(localRenderer,LinearLayout.LayoutParams(-1,220))
 box.addView(Button(this).apply{text="Check App Update";setOnClickListener{checkForUpdate(true)}});box.addView(Button(this).apply{text="Start LAN Discovery";setOnClickListener{startNetwork()}});box.addView(Button(this).apply{text="Initialize Audio + Camera";setOnClickListener{if(hasPermissions())initRtc()else requestPermissionsNow()}})
 box.addView(Button(this).apply{text="Call selected / all discovered peers";setOnClickListener{if(mesh==null){status.text="Initialize first"}else if(peers.isEmpty()){status.text="No peers found"}else{peers.values.forEach{mesh?.call(it.address)};status.text="Call offers sent to ${peers.size} peer(s)"}}})
 box.addView(Button(this).apply{text="Mute / Unmute";setOnClickListener{mute=!mute;mesh?.setMuted(mute);status.text=if(mute)"Microphone muted" else "Microphone active"}});box.addView(Button(this).apply{text="Camera On / Off";setOnClickListener{cam=!cam;mesh?.setVideoEnabled(cam);status.text=if(cam)"Video enabled" else "Video disabled"}})
 box.addView(Button(this).apply{text="Stop";setOnClickListener{discovery?.stop();signal?.stop();mesh?.close();mesh=null;status.text="Stopped"}});setContentView(ScrollView(this).apply{addView(box)});requestPermissionsNow();checkForUpdate(false) }

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

 private fun hasPermissions()=ActivityCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED&&ActivityCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
 private fun requestPermissionsNow(){val a=arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA);val missing=a.filter{ActivityCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED};if(missing.isNotEmpty())ActivityCompat.requestPermissions(this,missing.toTypedArray(),10)}
 private fun startNetwork(){signal?.stop();signal=SignalServer(onMessage={msg,addr->runOnUiThread{mesh?.receive(msg,addr)}}).also{it.start()};discovery?.stop();discovery=LanDiscovery(this,name.text.toString().ifBlank{"Android"}){p->runOnUiThread{peers[p.id]=p;peersView.text="Nearby peers:\n"+peers.values.joinToString("\n"){ "${it.name} — ${it.address}" }}}.also{it.start()};status.text="LAN discovery/signaling started. Connect phones to same hotspot/Wi-Fi."}
 private fun initRtc(){try{egl=EglBase.create();remoteRenderer.init(egl.eglBaseContext,null);localRenderer.init(egl.eglBaseContext,null);remoteRenderer.setEnableHardwareScaler(true);localRenderer.setEnableHardwareScaler(true);mesh=WebRtcMesh(this,signal?:SignalServer(onMessage={msg,addr->mesh?.receive(msg,addr)}).also{signal=it;it.start()},localId,{s->runOnUiThread{status.text=s}},{t->runOnUiThread{t.addSink(remoteRenderer)}},{t->runOnUiThread{t.addSink(localRenderer)}},egl.eglBaseContext).also{it.initialize()};status.text="Audio/video ready. Discover peers, then call."}catch(e:Exception){status.text="RTC initialization failed: ${e.message}"}}
 override fun onDestroy(){discovery?.stop();signal?.stop();mesh?.close();try{remoteRenderer.release();localRenderer.release();egl.release()}catch(_:Exception){};super.onDestroy()}
}
