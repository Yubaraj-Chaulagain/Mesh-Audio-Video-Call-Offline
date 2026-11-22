package com.example.meshcall

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.ConcurrentHashMap

/** Direct LAN WebRTC sessions. Each peer is a direct endpoint (not an RTP relay). */
class WebRtcMesh(private val context: Context, private val signal: SignalServer, private val localId: String,
                 private val onStatus: (String)->Unit, private val onRemoteVideo: (VideoTrack)->Unit,
                 private val onLocalVideo: (VideoTrack)->Unit, private val eglContext: EglBase.Context) {
    private val pcs=ConcurrentHashMap<String,PeerConnection>(); private val pendingIce=ConcurrentHashMap<String,MutableList<IceCandidate>>()
    private var factory:PeerConnectionFactory?=null; private var audio:AudioTrack?=null; private var video:VideoTrack?=null
    private var capturer:CameraVideoCapturer?=null; private var texture:SurfaceTextureHelper?=null; private var videoSource:VideoSource?=null
    fun initialize(){
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        factory=PeerConnectionFactory.builder().setVideoEncoderFactory(DefaultVideoEncoderFactory(null,true,true)).setVideoDecoderFactory(DefaultVideoDecoderFactory(null)).createPeerConnectionFactory()
        val f=factory!!; audio=f.createAudioTrack("mesh-audio",f.createAudioSource(MediaConstraints()))
        val src=f.createVideoSource(false); videoSource=src; texture=SurfaceTextureHelper.create("MeshCallCamera",eglContext)
        val cameraEnumerator=Camera2Enumerator(context); val cameraName=cameraEnumerator.deviceNames.firstOrNull{cameraEnumerator.isFrontFacing(it)} ?: cameraEnumerator.deviceNames.firstOrNull()
        if(cameraName!=null){ capturer=cameraEnumerator.createCapturer(cameraName,null); capturer?.initialize(texture,context,src.capturerObserver); capturer?.startCapture(640,480,24) }
        video=f.createVideoTrack("mesh-video",src); video?.let(onLocalVideo); onStatus("Audio and camera initialized")
    }
    private fun pc(address:String):PeerConnection? {
        factory?.let { if(pcs.containsKey(address))return pcs[address] }
        val f=factory?:return null
        val config=PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val created=f.createPeerConnection(config,object:PeerConnection.Observer{
            override fun onSignalingChange(s:PeerConnection.SignalingState){}
            override fun onIceConnectionChange(s:PeerConnection.IceConnectionState){onStatus("$address ICE: $s"); if(s==PeerConnection.IceConnectionState.FAILED||s==PeerConnection.IceConnectionState.DISCONNECTED)onStatus("Connection disrupted; retry call after route/network recovery")}
            override fun onIceConnectionReceivingChange(b:Boolean){}; override fun onIceGatheringChange(s:PeerConnection.IceGatheringState){}
            override fun onIceCandidate(c:IceCandidate){signal.send(address,JSONObject().put("type","ice").put("from",localId).put("sdp",c.sdp).put("mid",c.sdpMid).put("index",c.sdpMLineIndex))}
            override fun onIceCandidatesRemoved(c:Array<IceCandidate>){}; override fun onAddStream(s:MediaStream){}; override fun onRemoveStream(s:MediaStream){}
            override fun onDataChannel(d:DataChannel){}; override fun onRenegotiationNeeded(){}
            override fun onAddTrack(r:RtpReceiver,streams:Array<MediaStream>){(r.track() as? VideoTrack)?.let(onRemoteVideo)}
            override fun onTrack(t:RtpTransceiver){(t.receiver.track() as? VideoTrack)?.let(onRemoteVideo)}
        }) ?: return null
        created.addTrack(audio); video?.let{created.addTrack(it)}; pcs[address]=created
        pendingIce.remove(address)?.forEach{created.addIceCandidate(it)}
        return created
    }
    fun call(address:String){val p=pc(address)?:return;p.createOffer(object:SdpObserver{override fun onCreateSuccess(d:SessionDescription){p.setLocalDescription(object:SdpObserver{override fun onSetSuccess(){signal.send(address,JSONObject().put("type","offer").put("from",localId).put("sdp",d.description))};override fun onSetFailure(e:String){onStatus(e)};override fun onCreateSuccess(s:SessionDescription){};override fun onCreateFailure(e:String){}},d)};override fun onCreateFailure(e:String){onStatus(e)};override fun onSetSuccess(){};override fun onSetFailure(e:String){onStatus(e)}},MediaConstraints())}
    fun receive(msg:JSONObject,address:String){when(msg.optString("type")){"offer"->{val p=pc(address)?:return;val d=SessionDescription(SessionDescription.Type.OFFER,msg.getString("sdp"));p.setRemoteDescription(object:SdpObserver{override fun onSetSuccess(){p.createAnswer(object:SdpObserver{override fun onCreateSuccess(a:SessionDescription){p.setLocalDescription(object:SdpObserver{override fun onSetSuccess(){signal.send(address,JSONObject().put("type","answer").put("from",localId).put("sdp",a.description))};override fun onSetFailure(e:String){onStatus(e)};override fun onCreateSuccess(s:SessionDescription){};override fun onCreateFailure(e:String){}},a)};override fun onCreateFailure(e:String){onStatus(e)};override fun onSetSuccess(){};override fun onSetFailure(e:String){onStatus(e)}},MediaConstraints())};override fun onSetFailure(e:String){onStatus(e)};override fun onCreateSuccess(s:SessionDescription){};override fun onCreateFailure(e:String){}},d)};"answer"->{pcs[address]?.setRemoteDescription(object:SdpObserver{override fun onSetSuccess(){pendingIce.remove(address)?.forEach{pcs[address]?.addIceCandidate(it)}};override fun onSetFailure(e:String){onStatus(e)};override fun onCreateSuccess(d:SessionDescription){};override fun onCreateFailure(e:String){}},SessionDescription(SessionDescription.Type.ANSWER,msg.getString("sdp")))};"ice"->{val c=IceCandidate(msg.optString("mid"),msg.optInt("index"),msg.getString("sdp"));val p=pcs[address];if(p!=null)p.addIceCandidate(c)else pendingIce.computeIfAbsent(address){mutableListOf()}.add(c)}}}
    fun setMuted(m:Boolean){audio?.setEnabled(!m)}; fun setVideoEnabled(enabled:Boolean){video?.setEnabled(enabled)}
    fun close(){pcs.values.forEach{it.close()};pcs.clear();try{capturer?.stopCapture()}catch(_:Exception){};capturer?.dispose();texture?.dispose();video?.dispose();audio?.dispose();videoSource?.dispose();factory?.dispose();factory=null}
}
