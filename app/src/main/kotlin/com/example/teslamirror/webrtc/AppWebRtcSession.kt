package com.example.teslamirror.webrtc

import android.content.Context
import android.util.Log
import com.example.teslamirror.rendezvous.RendezvousUpdater
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 앱 모드 전용 WebRTC 전송 (전체화면 [WebRtcSession]과 **독립** — 부모님이 쓰는 전체화면
 * 경로를 건드리지 않기 위해 의도적으로 분리·복제했다).
 *
 * 전체화면과 동일한 스택: Cloudflare Worker 시그널링 + 공인 ICE(STUN) + **JPEG 데이터채널**.
 * 다만 프레임 소스가 MediaProjection이 아니라 **외부(scrcpy→H.264→JPEG)** 이므로,
 * 캡처를 내장하지 않고 [pushJpeg]로 밖에서 프레임을 밀어 넣는다.
 *
 * 뷰어(테슬라/노트북)는 전체화면과 같은 워커 뷰어를 그대로 쓴다(같은 deviceId·같은 JPEG
 * 데이터채널). 뷰어→폰 역방향 메시지(터치, Phase 2)는 [onViewerMessage]로 콜백한다.
 *
 * dispose() 금지 규칙 동일(close()만) — [[teslamirror-webrtc-dispose-crash]].
 */
class AppWebRtcSession(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    // 뷰어가 데이터채널로 보낸 텍스트(JSON) — Phase 2 터치 역제어용. Phase 1은 무시 가능.
    private val onViewerMessage: (String) -> Unit = {},
    // pc 연결 성립 시 1회 호출 — 새 뷰어가 디코드를 시작하려면 키프레임이 필요하므로
    // scrcpy에 IDR을 요청하는 데 쓴다(정적 화면에서도 첫 프레임이 뜨게).
    private val onConnected: () -> Unit = {},
    // 실차/테슬라 경로(STUN 공인 후보, 사설 제거). PC 핫스팟 로컬 디버그 시 false.
    private val internetPath: Boolean = true,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    @Volatile private var dataChannel: DataChannel? = null
    @Volatile private var running = false

    private var gathered = CompletableDeferred<Unit>()
    private var connected = CompletableDeferred<Unit>()
    private var closed = CompletableDeferred<Unit>()

    private val deviceId get() = RendezvousUpdater.deviceId(context)

    fun start() {
        running = true
        Log.i(TAG, "start deviceId=$deviceId internet=$internetPath")
        initFactory()
        scope.launch { negotiateLoop() }
    }

    fun stop() {
        running = false
        closeConnection()
        scope.cancel()
        // factory.dispose() 금지(SIGILL 위험) — 전체화면과 동일 규칙.
    }

    /** 뷰어로 보낼 수 있는 상태인가(연결됨 + 버퍼 여유). 디코더가 인코딩 스킵 판단에 사용. */
    fun canSend(): Boolean {
        val dc = dataChannel ?: return false
        return dc.state() == DataChannel.State.OPEN && dc.bufferedAmount() <= BUFFER_LIMIT
    }

    /** 외부(H.264→JPEG 디코더)가 프레임을 밀어 넣는다. */
    fun pushJpeg(jpeg: ByteArray) {
        val dc = dataChannel ?: return
        try {
            if (dc.state() != DataChannel.State.OPEN) return
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(jpeg), true))
        } catch (t: Throwable) {
            Log.w(TAG, "dc send failed", t)
        }
    }

    private fun initFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        val options = PeerConnectionFactory.Options().apply { disableNetworkMonitor = true }
        factory = PeerConnectionFactory.builder().setOptions(options).createPeerConnectionFactory()
    }

    private suspend fun negotiateLoop() {
        while (running) {
            try {
                onStatus("연결 대기 — 테슬라에서 접속하세요")
                createPeerConnection()
                val offerId = System.currentTimeMillis().toString()
                postOffer(offerId, createOfferAndGather())
                Log.i(TAG, "offer posted id=$offerId")
                val answer = awaitAnswer(offerId)
                if (answer == null) { closeConnection(); continue }
                setRemote(answer)
                val ok = withTimeoutOrNull(12_000) { connected.await() } != null
                if (!ok) { Log.i(TAG, "not connected in 12s, re-offering"); closeConnection(); continue }
                onStatus("연결됨")
                runCatching { onConnected() }   // 새 뷰어용 키프레임 요청 등
                awaitClosed()
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "negotiate error", t)
            } finally {
                closeConnection()
            }
            if (running) delay(500)
        }
    }

    private fun createPeerConnection() {
        gathered = CompletableDeferred()
        connected = CompletableDeferred()
        closed = CompletableDeferred()
        val g = gathered; val c = connected; val x = closed
        val iceServers = if (internetPath) listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        ) else emptyList()
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) { Log.i(TAG, "ice=$s") }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                Log.i(TAG, "pc=$s")
                when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED -> c.complete(Unit)
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.CLOSED -> x.complete(Unit)
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) g.complete(Unit)
            }
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
        val dc = pc!!.createDataChannel("v", DataChannel.Init().apply { ordered = true })
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer?) {
                // 뷰어→폰 역방향 메시지(Phase 2 터치). 바이너리(우리 JPEG)는 무시, 텍스트만.
                val b = buffer ?: return
                if (b.binary) return
                val data = ByteArray(b.data.remaining()).also { b.data.get(it) }
                runCatching { onViewerMessage(String(data, Charsets.UTF_8)) }
            }
        })
        dataChannel = dc
    }

    private suspend fun createOfferAndGather(): String {
        val offer = suspendCancellableCoroutine<SessionDescription> { cont ->
            pc!!.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { cont.resume(sdp) }
                override fun onCreateFailure(e: String?) { cont.resumeWithException(RuntimeException("createOffer: $e")) }
                override fun onSetSuccess() {}
                override fun onSetFailure(e: String?) {}
            }, MediaConstraints())
        }
        awaitSet("setLocal") { pc!!.setLocalDescription(it, offer) }
        withTimeoutOrNull(5000) { gathered.await() }
        val raw = pc!!.localDescription?.description ?: offer.description
        val filtered = if (internetPath) filterToPublicCandidates(raw) else filterToLocalCandidates(raw)
        val hasCand = Regex("""a=candidate:""").containsMatchIn(filtered)
        if (!hasCand) {
            Log.w(TAG, "no candidate after filter internet=$internetPath — fallback raw")
            onStatus(if (internetPath) "공인 ICE 후보 없음 — 셀룰러/STUN 확인" else "로컬 후보 없음 — 핫스팟 확인")
        }
        val sdp = if (hasCand) filtered else raw
        Log.i(TAG, "offer ready cands=${Regex("""a=candidate:""").findAll(sdp).count()} internet=$internetPath")
        return sdp
    }

    private fun filterToLocalCandidates(sdp: String): String =
        sdp.split("\r\n", "\n").map { it.trimEnd('\r') }.filter { line ->
            if (!line.startsWith("a=candidate:")) return@filter true
            val addr = line.removePrefix("a=").split(' ').getOrNull(4) ?: return@filter false
            isPrivateIpv4(addr)
        }.joinToString("\r\n").trimEnd() + "\r\n"

    private fun filterToPublicCandidates(sdp: String): String =
        sdp.split("\r\n", "\n").map { it.trimEnd('\r') }.filter { line ->
            if (!line.startsWith("a=candidate:")) return@filter true
            val addr = line.removePrefix("a=").split(' ').getOrNull(4) ?: return@filter false
            !isPrivateIpv4(addr) && addr != "0.0.0.0"
        }.joinToString("\r\n").trimEnd() + "\r\n"

    private fun isPrivateIpv4(a: String): Boolean {
        val m = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").find(a) ?: return false
        val p = m.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
        if (p.any { it > 255 }) return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }

    private suspend fun setRemote(answerSdp: String) =
        awaitSet("setRemote") {
            pc!!.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        }

    private suspend fun awaitSet(op: String, apply: (SdpObserver) -> Unit) {
        suspendCancellableCoroutine<Unit> { cont ->
            apply(object : SdpObserver {
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onSetFailure(e: String?) { cont.resumeWithException(RuntimeException("$op: $e")) }
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(e: String?) {}
            })
        }
    }

    private suspend fun awaitClosed() {
        while (running) {
            val st = pc?.connectionState() ?: return
            if (st == PeerConnection.PeerConnectionState.FAILED ||
                st == PeerConnection.PeerConnectionState.DISCONNECTED ||
                st == PeerConnection.PeerConnectionState.CLOSED
            ) return
            withTimeoutOrNull(2000) { closed.await() }
        }
    }

    private fun closeConnection() {
        runCatching { dataChannel?.close() }
        runCatching { pc?.close() }
        dataChannel = null; pc = null
    }

    private suspend fun postOffer(offerId: String, sdp: String) {
        val body = JSONObject().put("deviceId", deviceId).put("offerId", offerId).put("sdp", sdp).toString()
        runCatching { RendezvousUpdater.postJson(context, "/offer", body) }
    }

    private suspend fun awaitAnswer(offerId: String): String? {
        val start = System.currentTimeMillis()
        var lastRepost = start
        while (running) {
            fetchAnswer(offerId)?.let { return it }
            val now = System.currentTimeMillis()
            if (now - lastRepost > 100_000) {
                val d = pc?.localDescription?.description ?: return null
                postOffer(offerId, if (internetPath) filterToPublicCandidates(d) else filterToLocalCandidates(d))
                lastRepost = now
            }
            delay(if (now - start < 20_000) 700 else 2500)
        }
        return null
    }

    private suspend fun fetchAnswer(offerId: String): String? {
        val txt = runCatching { RendezvousUpdater.getBody("/answer?id=$deviceId") }.getOrNull() ?: return null
        return runCatching {
            val o = JSONObject(txt)
            if (o.optString("offerId") == offerId) o.getString("sdp") else null
        }.getOrNull()
    }

    companion object {
        private const val TAG = "AppWebRtcSession"
        private const val BUFFER_LIMIT = 96 * 1024L
    }
}
