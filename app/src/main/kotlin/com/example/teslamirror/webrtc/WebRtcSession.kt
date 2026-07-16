package com.example.teslamirror.webrtc

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.util.Log
import com.example.teslamirror.capture.MjpegCapturer
import com.example.teslamirror.rendezvous.RendezvousUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 전체화면 모드 전송 (저지연 우선).
 *
 * H.264 미디어 트랙 대신 **JPEG 프레임을 WebRTC 데이터 채널**로 흘린다.
 * 프레임이 독립적이라 지터 버퍼/GOP 지연이 없어(내비 실시간성에 유리), 오는 즉시
 * 캔버스에 그린다. 전송은 WebRTC라 로컬 핫스팟 P2P 경로를 타고(사설 IP 차단 회피),
 * 캔버스+JPEG는 구형 테슬라 브라우저에서도 무조건 동작한다.
 */
class WebRtcSession(
    private val context: Context,
    private val resultCode: Int,
    private val projectionData: Intent,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val onStatus: (String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var factory: PeerConnectionFactory
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var capturer: MjpegCapturer? = null
    private var pc: PeerConnection? = null
    @Volatile private var dataChannel: DataChannel? = null
    @Volatile private var running = false

    private val deviceId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    private val secret get() = RendezvousUpdater.secret(context)
    private val base get() = RendezvousUpdater.WORKER_URL

    fun start() {
        running = true
        Log.i(TAG, "start deviceId=$deviceId ${width}x$height@$fps")
        initFactory()
        startCapture()
        scope.launch { negotiateLoop() }
    }

    fun stop() {
        running = false
        runCatching { dataChannel?.close() }
        runCatching { pc?.close() }
        runCatching { capturer?.stop() }
        runCatching { virtualDisplay?.release() }
        runCatching { projection?.stop() }
        runCatching { factory.dispose() }
        dataChannel = null; pc = null; capturer = null; virtualDisplay = null; projection = null
        scope.cancel()
    }

    private fun initFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        // 폰이 핫스팟 AP라 Android NetworkMonitor가 swlan0 IPv4를 열거 못 함 →
        // disableNetworkMonitor로 네이티브 열거를 써야 로컬 후보(핫스팟 IP)가 잡힌다.
        val options = PeerConnectionFactory.Options().apply { disableNetworkMonitor = true }
        factory = PeerConnectionFactory.builder().setOptions(options).createPeerConnectionFactory()
    }

    private fun startCapture() {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, projectionData).also { projection = it }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.i(TAG, "MediaProjection stopped") }
        }, null)
        val cap = MjpegCapturer(width, height, fps = fps, quality = 60) { jpeg -> sendFrame(jpeg) }
        capturer = cap
        val metrics = context.resources.displayMetrics
        virtualDisplay = proj.createVirtualDisplay(
            "TeslaMirror", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            cap.surface, null, null
        )
        cap.start(scope)
    }

    /** JPEG를 데이터 채널로 전송. 버퍼가 쌓였으면(느린 소비자) 프레임을 버려 지연을 낮게 유지. */
    private fun sendFrame(jpeg: ByteArray) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        if (dc.bufferedAmount() > BUFFER_LIMIT) return  // 최신 프레임 우선, 밀린 건 드롭
        try {
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(jpeg), true))
        } catch (t: Throwable) {
            Log.w(TAG, "dc send failed", t)
        }
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
                val answer = awaitAnswer(offerId)
                if (answer == null) { closePc(); continue }
                setRemote(answer)
                Log.i(TAG, "answer applied, awaiting connection")
                if (!awaitConnected(12_000)) { Log.i(TAG, "not connected, re-offering"); closePc(); continue }
                onStatus("연결됨")
                awaitPcClosed()
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "negotiate error", t)
            } finally {
                closePc()
            }
            if (running) delay(500)
        }
    }

    private fun createPeerConnection() {
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {   // STUN 없음: 로컬 host만
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) { Log.i(TAG, "ice=$s") }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                Log.i(TAG, "pc=$s")
                if (s == PeerConnection.PeerConnectionState.CONNECTED)
                    connectedSignal?.let { runCatching { it.resume(Unit) }; connectedSignal = null }
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
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
        // 폰이 데이터 채널을 만든다(신뢰·순서 보장; 로컬망은 무손실이라 지연 영향 없음).
        val init = DataChannel.Init().apply { ordered = true }
        dataChannel = pc!!.createDataChannel("v", init)
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
        withTimeoutOrNull(5000) {
            if (pc!!.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
                suspendCancellableCoroutine<Unit> { cont -> gatherSignal = cont }
            }
        }
        gatherSignal = null
        val sdp = filterToLocalCandidates(pc!!.localDescription?.description ?: offer.description)
        val n = Regex("""a=candidate:[^\r\n]*""").findAll(sdp).count()
        Log.i(TAG, "offer ready localCandidates=$n")
        return sdp
    }

    /** SDP에서 사설 IPv4 host 후보만 남긴다(셀룰러/공인 IPv6 제거 → 로컬 경로 강제). */
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

    private suspend fun awaitConnected(timeoutMs: Long): Boolean {
        if (pc?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) return true
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont -> connectedSignal = cont }
        } != null
    }

    private suspend fun awaitPcClosed() {
        suspendCancellableCoroutine<Unit> { cont -> pcClosedSignal = cont }
    }

    private fun closePc() {
        gatherSignal = null; pcClosedSignal = null; connectedSignal = null
        runCatching { dataChannel?.close() }
        runCatching { pc?.close() }
        dataChannel = null; pc = null
    }

    // ---- 시그널링 ----
    private suspend fun postOffer(offerId: String, sdp: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("deviceId", deviceId).put("offerId", offerId).put("sdp", sdp).toString()
        val conn = (URL("$base/offer").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $secret")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode
        conn.disconnect()
    }

    private suspend fun awaitAnswer(offerId: String): String? {
        var lastRepost = System.currentTimeMillis()
        while (running) {
            fetchAnswer(offerId)?.let { return it }
            if (System.currentTimeMillis() - lastRepost > 45_000) {
                pc?.localDescription?.description?.let { postOffer(offerId, filterToLocalCandidates(it)) }
                lastRepost = System.currentTimeMillis()
            }
            delay(700)
        }
        return null
    }

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
            } else conn.disconnect()
        } catch (_: Throwable) {}
        null
    }

    companion object {
        private const val TAG = "WebRtcSession"
        private const val BUFFER_LIMIT = 96 * 1024L  // 데이터채널 버퍼 상한(~1.5프레임)
    }
}
