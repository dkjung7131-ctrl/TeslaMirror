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
        // 폰이 핫스팟 AP라 Android NetworkMonitor(ConnectivityManager)가 swlan0의 IPv4를
        // 열거하지 못한다 → 로컬 후보가 안 생기고 셀룰러 경로만 남아 지연 폭증.
        // disableNetworkMonitor로 네이티브 getifaddrs 열거를 쓰면 swlan0 IPv4도 잡힌다.
        val options = PeerConnectionFactory.Options().apply { disableNetworkMonitor = true }
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
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
                onStatus("연결 대기 — 테슬라에서 접속하세요")
                createPeerConnection()
                val offerId = System.currentTimeMillis().toString()
                val offerSdp = createOfferAndGather()
                postOffer(offerId, offerSdp)
                Log.i(TAG, "offer posted id=$offerId")
                // 오퍼ID를 안정적으로 유지하며 앤서를 기다린다. 오퍼를 자주 바꾸면
                // KV 지연/경합으로 뷰어 앤서와 offerId가 어긋나므로, 재오퍼는 연결
                // 실패 시에만 한다.
                val answer = awaitAnswer(offerId)
                if (answer == null) { closePc(); continue }
                setRemote(answer)
                Log.i(TAG, "answer applied, awaiting connection")
                if (!awaitConnected(12_000)) {
                    Log.i(TAG, "not connected in 12s, re-offering")
                    closePc(); continue
                }
                onStatus("연결됨")
                awaitPcClosed()  // 끊길 때까지 유지
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "negotiate error", t)
            } finally {
                closePc()
            }
            if (running) delay(500)
        }
    }

    /** running 동안 앤서를 폴링. 45초마다 같은 오퍼를 재게시해 KV TTL을 갱신. */
    private suspend fun awaitAnswer(offerId: String): String? {
        var lastRepost = System.currentTimeMillis()
        while (running) {
            fetchAnswer(offerId)?.let { return it }
            if (System.currentTimeMillis() - lastRepost > 45_000) {
                val sdp = pc?.localDescription?.description ?: return null
                postOffer(offerId, sdp)
                lastRepost = System.currentTimeMillis()
            }
            delay(1500)
        }
        return null
    }

    private suspend fun awaitConnected(timeoutMs: Long): Boolean {
        if (pc?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) return true
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont -> connectedSignal = cont }
        } != null
    }

    private fun createPeerConnection() {
        // STUN 없음: 뷰어(테슬라)는 항상 같은 핫스팟에 있으므로 host 후보만으로 연결.
        // srflx(셀룰러 NAT 경유) 후보가 끼면 인터넷 왕복 경로가 선택돼 지연이 폭증한다.
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
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
                if (s == PeerConnection.PeerConnectionState.CONNECTED) {
                    onStatus("연결됨 — 재생 중")
                    connectedSignal?.let { runCatching { it.resume(Unit) }; connectedSignal = null }
                }
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
    private var connectedSignal: kotlin.coroutines.Continuation<Unit>? = null

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
        val rawSdp = pc!!.localDescription?.description ?: offer.description
        Regex("""a=candidate:[^\r\n]*""").findAll(rawSdp).forEach { Log.i(TAG, "  raw: ${it.value.take(72)}") }
        // 핫스팟 로컬 후보(사설 IPv4)만 남긴다 — 셀룰러/공인 IPv6 후보가 끼면 통신사 망 왕복.
        val sdp = filterToLocalCandidates(rawSdp)
        val kept = Regex("""a=candidate:[^\r\n]*""").findAll(sdp).map { it.value }.toList()
        Log.i(TAG, "offer ready localCandidates=${kept.size}")
        kept.forEach { Log.i(TAG, "  keep: ${it.take(72)}") }
        return sdp
    }

    /** SDP에서 사설 IPv4(10/8, 172.16/12, 192.168/16) host 후보만 남기고 나머지 후보 줄 제거. */
    private fun filterToLocalCandidates(sdp: String): String {
        val out = sdp.split("\r\n", "\n").map { it.trimEnd('\r') }.filter { line ->
            if (!line.startsWith("a=candidate:")) return@filter true
            val addr = line.removePrefix("a=").split(' ').getOrNull(4) ?: return@filter false
            isPrivateIpv4(addr)
        }
        return out.joinToString("\r\n").trimEnd() + "\r\n"
    }

    private fun isPrivateIpv4(a: String): Boolean {
        val m = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").find(a) ?: return false
        val p = m.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
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
        connectedSignal = null
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

    /** 앤서를 한 번 조회. offerId가 일치하면 sdp, 아니면 null. */
    private suspend fun fetchAnswer(offerId: String): String? = withContext(Dispatchers.IO) {
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
        null
    }

    companion object {
        private const val TAG = "WebRtcSession"
    }
}
