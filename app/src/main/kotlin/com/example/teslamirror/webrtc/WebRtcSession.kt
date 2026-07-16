package com.example.teslamirror.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.provider.Settings
import android.util.Log
import com.example.teslamirror.rendezvous.RendezvousUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 전체화면 모드 WebRTC 세션.
 *
 * MediaProjection 화면을 WebRTC VideoTrack으로 만들어, 워커 시그널링(offer/answer)을
 * 거쳐 테슬라 브라우저와 로컬 P2P로 연결한다. ICE가 같은 핫스팟의 host 후보를 찾아
 * 영상이 로컬 직통으로 흐른다(저지연, 인터넷 왕복 없음).
 *
 * 시그널링: 폰이 offerer. 오퍼 게시 → 앤서 폴링. 연결이 끊기면 새 오퍼로 재협상.
 */
class WebRtcSession(
    private val context: Context,
    private val projectionData: Intent,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val onStatus: (String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var pc: PeerConnection? = null
    @Volatile private var running = false

    private val deviceId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    private val secret get() = RendezvousUpdater.secret(context)
    private val base get() = RendezvousUpdater.WORKER_URL

    fun start() {
        running = true
        Log.i(TAG, "start deviceId=$deviceId secretSet=${secret.isNotBlank()} ${width}x$height@$fps")
        initFactory()
        startCapture()
        scope.launch { negotiateLoop() }
    }

    fun stop() {
        running = false
        runCatching { pc?.close() }; pc = null
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { factory.dispose() }
        runCatching { eglBase.release() }
        scope.cancel()
    }

    private fun initFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun startCapture() {
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val cap = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
            }
        })
        capturer = cap
        val source = factory.createVideoSource(true) // isScreencast=true
        videoSource = source
        cap.initialize(surfaceHelper, context, source.capturerObserver)
        cap.startCapture(width, height, fps)
        videoTrack = factory.createVideoTrack("video0", source)
    }

    private suspend fun negotiateLoop() {
        while (running) {
            try {
                val offerId = System.currentTimeMillis().toString()
                onStatus("연결 대기 — 테슬라에서 접속하세요")
                createPeerConnection()
                val offerSdp = createOfferAndGather()
                val code = postOffer(offerId, offerSdp)
                Log.i(TAG, "offer posted id=$offerId http=$code")
                val answer = pollAnswer(offerId, timeoutMs = 25_000)
                if (answer == null) {
                    Log.i(TAG, "no answer, re-offering")
                    closePc()
                    continue
                }
                setRemote(answer)
                onStatus("연결됨")
                // 연결이 유지되는 동안 대기; 끊기면 재협상
                awaitPcClosed()
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "negotiate error", t)
            } finally {
                closePc()
            }
            if (running) delay(1000)
        }
    }

    private fun createPeerConnection() {
        val cfg = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "ice=$s")
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                Log.i(TAG, "pc=$s")
                if (s == PeerConnection.PeerConnectionState.CONNECTED) onStatus("연결됨 — 재생 중")
                if (s == PeerConnection.PeerConnectionState.FAILED ||
                    s == PeerConnection.PeerConnectionState.DISCONNECTED ||
                    s == PeerConnection.PeerConnectionState.CLOSED
                ) pcClosedSignal?.let { runCatching { it.resume(Unit) }; pcClosedSignal = null }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                if (s == PeerConnection.IceGatheringState.COMPLETE)
                    gatherSignal?.let { runCatching { it.resume(Unit) }; gatherSignal = null }
            }
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
        pc!!.addTrack(videoTrack, listOf("stream0"))
    }

    private var gatherSignal: kotlin.coroutines.Continuation<Unit>? = null
    private var pcClosedSignal: kotlin.coroutines.Continuation<Unit>? = null

    private suspend fun createOfferAndGather(): String {
        val offer = suspendCancellableCoroutine<SessionDescription> { cont ->
            pc!!.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { cont.resume(sdp) }
                override fun onCreateFailure(e: String?) { cont.resumeWithException(RuntimeException("createOffer: $e")) }
                override fun onSetSuccess() {}
                override fun onSetFailure(e: String?) {}
            }, MediaConstraints())
        }
        suspendCancellableCoroutine<Unit> { cont ->
            pc!!.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onSetFailure(e: String?) { cont.resumeWithException(RuntimeException("setLocal: $e")) }
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(e: String?) {}
            }, offer)
        }
        // non-trickle: ICE 수집을 최대 5초 대기(로컬 핫스팟은 host 후보만으로 충분).
        // 완료 신호를 놓치는 경합이 있어도 타임아웃으로 진행한다.
        withTimeoutOrNull(5000) {
            if (pc!!.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
                suspendCancellableCoroutine<Unit> { cont -> gatherSignal = cont }
            }
        }
        gatherSignal = null
        val sdp = pc!!.localDescription?.description ?: offer.description
        val nCand = Regex("a=candidate").findAll(sdp).count()
        Log.i(TAG, "offer ready, gathering=${pc!!.iceGatheringState()} candidates=$nCand")
        return sdp
    }

    private suspend fun setRemote(answerSdp: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            pc!!.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onSetFailure(e: String?) { cont.resumeWithException(RuntimeException("setRemote: $e")) }
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(e: String?) {}
            }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        }
    }

    private suspend fun awaitPcClosed() {
        suspendCancellableCoroutine<Unit> { cont -> pcClosedSignal = cont }
    }

    private fun closePc() {
        gatherSignal = null
        pcClosedSignal = null
        runCatching { pc?.close() }
        pc = null
    }

    // ---- 시그널링 HTTP ----
    private suspend fun postOffer(offerId: String, sdp: String): Int = withContext(Dispatchers.IO) {
        val body = JSONObject().put("deviceId", deviceId).put("offerId", offerId).put("sdp", sdp).toString()
        val conn = (URL("$base/offer").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $secret")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        conn.disconnect()
        code
    }

    private suspend fun pollAnswer(offerId: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running && System.currentTimeMillis() < deadline) {
            try {
                val conn = (URL("$base/answer?id=$deviceId").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000; readTimeout = 8_000
                }
                if (conn.responseCode == 200) {
                    val txt = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    conn.disconnect()
                    val o = JSONObject(txt)
                    if (o.optString("offerId") == offerId) return@withContext o.getString("sdp")
                } else {
                    conn.disconnect()
                }
            } catch (_: Throwable) {}
            delay(1500)
        }
        null
    }

    companion object {
        private const val TAG = "WebRtcSession"
    }
}
