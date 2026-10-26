package app.amend.messenger.util

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A STUN or TURN server. */
data class IceServerConfig(val url: String, val username: String = "", val password: String = "")

/** Process-wide WebRTC objects (created once, on first call). */
object Rtc {
    val egl: EglBase by lazy { EglBase.create() }

    @Volatile private var factory: PeerConnectionFactory? = null

    fun factory(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        synchronized(this) {
            factory?.let { return it }
            val app = context.applicationContext
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
            val audio = JavaAudioDeviceModule.builder(app)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            val f = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audio)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            factory = f
            return f
        }
    }
}

/**
 * One WebRTC call: microphone (and camera) out, the friend's audio/video in.
 * Media flows directly between the two phones when possible, always encrypted
 * with DTLS-SRTP. ICE candidates are gathered up front and sent inside the SDP,
 * so a call needs just one offer and one answer over the relays.
 */
class RtcSession(
    private val context: Context,
    val video: Boolean,
    iceServers: List<IceServerConfig>,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnectionState(state: PeerConnection.PeerConnectionState)
        fun onRemoteVideo(track: VideoTrack)
    }

    private val factory = Rtc.factory(context)
    private val hasTurn = iceServers.any { it.url.startsWith("turn") }
    private val gathered = CompletableDeferred<Unit>()
    @Volatile private var closed = false

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) boostBitrate()
            listener.onConnectionState(newState)
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) listener.onRemoteVideo(track)
        }
    }

    private val pc: PeerConnection = factory.createPeerConnection(
        PeerConnection.RTCConfiguration(iceServers.map { s ->
            PeerConnection.IceServer.builder(s.url).apply {
                if (s.username.isNotBlank()) setUsername(s.username)
                if (s.password.isNotBlank()) setPassword(s.password)
            }.createIceServer()
        }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        },
        observer,
    ) ?: throw IllegalStateException("WebRTC is not available on this phone")

    private val audioSource: AudioSource = factory.createAudioSource(MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
    })
    val audioTrack: AudioTrack = factory.createAudioTrack("amend-audio", audioSource)

    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    var localVideo: VideoTrack? = null
        private set
    var frontCamera = true
        private set

    init {
        pc.addTrack(audioTrack, listOf("amend"))
        if (video) startCamera()
    }

    private fun startCamera() {
        val enumerator: CameraEnumerator =
            if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: return
        frontCamera = enumerator.isFrontFacing(name)
        val cap = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("amend-camera", Rtc.egl.eglBaseContext)
        val source = factory.createVideoSource(false)
        cap.initialize(helper, context, source.capturerObserver)
        cap.startCapture(1280, 720, 30) // 720p30: sharp, and the encoder scales down on weak networks
        val track = factory.createVideoTrack("amend-video", source)
        pc.addTrack(track, listOf("amend"))
        capturer = cap
        textureHelper = helper
        videoSource = source
        localVideo = track
    }

    /** Caller: our offer, with every ICE candidate already inside. */
    suspend fun createOffer(): String {
        val offer = createSdp(isOffer = true)
        setLocal(SessionDescription(offer.type, tuneAudio(offer.description)))
        return finalDescription()
    }

    /** Callee: takes the caller's offer, returns our answer. */
    suspend fun answer(remoteOffer: String): String {
        setRemote(SessionDescription(SessionDescription.Type.OFFER, remoteOffer))
        val answer = createSdp(isOffer = false)
        setLocal(SessionDescription(answer.type, tuneAudio(answer.description)))
        return finalDescription()
    }

    /** Caller: the callee's answer arrived. */
    suspend fun acceptAnswer(remoteAnswer: String) {
        setRemote(SessionDescription(SessionDescription.Type.ANSWER, remoteAnswer))
    }

    private suspend fun finalDescription(): String {
        // wait for STUN/TURN candidates (usually well under a second)
        // a TURN server far away (e.g. over TCP/443) can take a few seconds to answer
        withTimeoutOrNull(if (hasTurn) 7_000 else 4_000) { gathered.await() }
        return pc.localDescription?.description ?: throw IllegalStateException("no local description")
    }

    private suspend fun createSdp(isOffer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) { if (cont.isActive) cont.resume(desc) }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "create failed")) }
            override fun onSetFailure(error: String?) {}
        }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", video.toString()))
        }
        if (isOffer) pc.createOffer(obs, constraints) else pc.createAnswer(obs, constraints)
    }

    private suspend fun setLocal(desc: SessionDescription) = setDescription(desc, local = true)
    private suspend fun setRemote(desc: SessionDescription) = setDescription(desc, local = false)

    private suspend fun setDescription(desc: SessionDescription, local: Boolean): Unit = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "set failed")) }
        }
        if (local) pc.setLocalDescription(obs, desc) else pc.setRemoteDescription(obs, desc)
    }

    /** High-quality voice: Opus at up to 64 kbit/s, with forward error correction for lossy networks. */
    private fun tuneAudio(sdp: String): String {
        val pt = Regex("a=rtpmap:(\\d+) opus/48000").find(sdp)?.groupValues?.get(1) ?: return sdp
        return sdp.lines().joinToString("\r\n") { line ->
            if (line.startsWith("a=fmtp:$pt ") && !line.contains("maxaveragebitrate")) {
                line.trimEnd() + ";maxaveragebitrate=64000" + (if (line.contains("useinbandfec")) "" else ";useinbandfec=1")
            } else line
        }
    }

    /** Lets video use up to 2.5 Mbit/s once connected (WebRTC still adapts to the network). */
    private fun boostBitrate() {
        runCatching {
            pc.senders.forEach { sender ->
                if (sender.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    val params = sender.parameters
                    params.encodings.forEach { it.maxBitrateBps = 2_500_000 }
                    sender.parameters = params
                }
            }
        }
    }

    fun setMuted(muted: Boolean) { audioTrack.setEnabled(!muted) }

    fun setCameraOn(on: Boolean) { localVideo?.setEnabled(on) }

    fun switchCamera(done: (Boolean) -> Unit) {
        val cap = capturer ?: return
        cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { frontCamera = isFront; done(isFront) }
            override fun onCameraSwitchError(error: String?) {}
        })
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { pc.close() }
        runCatching { pc.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { textureHelper?.dispose() }
        runCatching { audioSource.dispose() }
        capturer = null
        localVideo = null
    }
}

/**
 * Settings "Test" button: asks the TURN servers for a relay address. If one comes
 * back, calls across strict networks (carrier NAT) will be able to connect.
 */
suspend fun testTurnServers(context: Context, servers: List<IceServerConfig>): Boolean {
    if (servers.isEmpty()) return false
    val factory = Rtc.factory(context)
    val done = CompletableDeferred<Boolean>()
    val config = PeerConnection.RTCConfiguration(servers.map { s ->
        PeerConnection.IceServer.builder(s.url).setUsername(s.username).setPassword(s.password).createIceServer()
    }).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        iceTransportsType = PeerConnection.IceTransportsType.RELAY
    }
    val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) done.complete(false)
        }
        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate?.sdp?.contains("typ relay") == true) done.complete(true)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }) ?: return false
    return try {
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
        val offer = suspendCancellableCoroutine<SessionDescription> { c ->
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription) { if (c.isActive) c.resume(d) }
                override fun onSetSuccess() {}
                override fun onCreateFailure(e: String?) { if (c.isActive) c.resumeWithException(IllegalStateException(e)) }
                override fun onSetFailure(e: String?) {}
            }, MediaConstraints())
        }
        pc.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) { done.complete(false) }
        }, offer)
        withTimeoutOrNull(10_000) { done.await() } ?: false
    } catch (e: Exception) {
        false
    } finally {
        runCatching { pc.dispose() }
    }
}
