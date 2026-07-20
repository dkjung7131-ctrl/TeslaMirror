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

    /**
     * 뷰어로 제어 JSON.
     * binary=true 로 보냄 — 일부 브라우저/조합에서 text 프레임이 JPEG 폭주 때 묻히거나
     * onmessage 타입이 달라 파싱이 스킵되는 경우가 있어, 작은 binary 로 통일.
     * (뷰어는 payload 선두 '{' 로 JPEG 와 구분)
     */
    fun pushControlJson(json: String) {
        val dc = dataChannel ?: return
        try {
            if (dc.state() != DataChannel.State.OPEN) return
            val bytes = json.toByteArray(Charsets.UTF_8)
            val ok = dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), /* binary */ true))
            if (!ok) Log.w(TAG, "dc control send returned false len=${bytes.size}")
            else Log.i(TAG, "dc control sent ${bytes.size}B ${json.take(60)}")
        } catch (t: Throwable) {
            Log.w(TAG, "dc control send failed", t)
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
                // 너무 길면 뷰어가 옛 offer에 묶인 채 "연결 중" 고착. 18s면 STUN 여유 있음.
                val waitMs = if (internetPath) 18_000L else 12_000L
                onStatus("ICE 연결 중…")
                val ok = withTimeoutOrNull(waitMs) { connected.await() } != null
                if (!ok) {
                    Log.i(TAG, "not connected in ${waitMs}ms, re-offering state=${pc?.connectionState()}")
                    closeConnection(); continue
                }
                onStatus("연결됨")
                runCatching { onConnected() }   // 새 뷰어용 키프레임 요청 등
                awaitClosed()
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "negotiate error", t)
            } finally {
                closeConnection()
            }
            // 재협상 간격 (너무 짧으면 깜빡임, 너무 길면 "연결중" 체감↑)
            if (running) delay(800)
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
            // offer 전 후보 미리 모아 첫 접속 지연 감소
            if (internetPath) iceCandidatePoolSize = 4
        }
        pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) { Log.i(TAG, "ice=$s") }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                Log.i(TAG, "pc=$s")
                when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        // 일시 disconnected 후 복구 가능 — 세션 종료로 보지 않음
                        if (!c.isCompleted) c.complete(Unit)
                    }
                    // DISCONNECTED 는 ICE 일시 끊김(수 초 복구 흔함). 여기서 끝내면
                    // 폰이 바로 re-offer → 뷰어가 연결중/재연결중 깜빡임.
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> {
                        if (!x.isCompleted) x.complete(Unit)
                    }
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
            override fun onStateChange() {
                val st = runCatching { dc.state() }.getOrNull()
                Log.i(TAG, "dc=$st")
                // 뷰어(브라우저) 종료 시 DC 먼저 닫힘 → 즉시 re-offer (옛 offer에 새 뷰어 붙는 고착 방지)
                if (st == DataChannel.State.CLOSED || st == DataChannel.State.CLOSING) {
                    if (c.isCompleted && !x.isCompleted) x.complete(Unit)
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                // 뷰어→폰 JSON 제어. 브라우저는 string 또는 binary UTF-8 로 보낼 수 있음.
                val b = buffer ?: return
                val data = ByteArray(b.data.remaining()).also { b.data.get(it) }
                val s = runCatching { String(data, Charsets.UTF_8) }.getOrNull() ?: return
                if (!s.startsWith("{")) {
                    Log.w(TAG, "viewer msg ignored binary=${b.binary} len=${data.size}")
                    return
                }
                Log.i(TAG, "viewer msg binary=${b.binary} ${s.take(80)}")
                runCatching { onViewerMessage(s) }
                    .onFailure { Log.w(TAG, "viewer msg handle failed", it) }
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
        // 전체 gather(최대 5s) 대신 유용 후보가 모이면 바로 offer 게시 → 첫 연결 체감 단축
        waitForUsefulIce(internetPath)
        val raw = pc!!.localDescription?.description ?: offer.description
        // internetPath: 사설 host + 공인 srflx 동시 (집 LAN + 테슬라). 루프백만 제거.
        val filtered = if (internetPath) filterJunkCandidates(raw) else filterToLocalCandidates(raw)
        val hasCand = Regex("""a=candidate:""").containsMatchIn(filtered)
        if (!hasCand) {
            Log.w(TAG, "no candidate after filter internet=$internetPath — fallback raw")
            onStatus(if (internetPath) "ICE 후보 없음 — 네트워크 확인" else "로컬 후보 없음 — Wi-Fi/핫스팟 확인")
        }
        val sdp = if (hasCand) filtered else raw
        Log.i(TAG, "offer ready cands=${Regex("""a=candidate:""").findAll(sdp).count()} internet=$internetPath")
        return sdp
    }

    /** gather complete 또는 유용 후보( srflx / host ) 확보 시 조기 종료. 상한 ~1.8s */
    private suspend fun waitForUsefulIce(internet: Boolean) {
        val deadline = System.currentTimeMillis() + 1_800L
        while (running && System.currentTimeMillis() < deadline) {
            if (gathered.isCompleted) return
            val sdp = pc?.localDescription?.description.orEmpty()
            if (internet) {
                if (sdp.contains("typ srflx") || sdp.contains("typ relay")) return
            } else if (Regex("""a=candidate:.*typ host""").containsMatchIn(sdp)) {
                return
            }
            delay(50)
        }
        withTimeoutOrNull(100) { if (!gathered.isCompleted) gathered.await() }
    }

    private fun filterToLocalCandidates(sdp: String): String =
        sdp.split("\r\n", "\n").map { it.trimEnd('\r') }.filter { line ->
            if (!line.startsWith("a=candidate:")) return@filter true
            val addr = line.removePrefix("a=").split(' ').getOrNull(4) ?: return@filter false
            isPrivateIpv4(addr)
        }.joinToString("\r\n").trimEnd() + "\r\n"

    private fun filterJunkCandidates(sdp: String): String =
        sdp.split("\r\n", "\n").map { it.trimEnd('\r') }.filter { line ->
            if (!line.startsWith("a=candidate:")) return@filter true
            val addr = line.removePrefix("a=").split(' ').getOrNull(4) ?: return@filter false
            addr != "0.0.0.0" && addr != "127.0.0.1" && !addr.startsWith("192.0.0.")
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
        // DISCONNECTED 는 잠시 후 CONNECTED 로 돌아올 수 있음 → 즉시 종료하지 않음.
        // FAILED/CLOSED 만 세션 끝. DISCONNECTED 가 오래 가면 별도 타임아웃.
        var disconnectedSince = 0L
        while (running) {
            val st = pc?.connectionState() ?: return
            when (st) {
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> return
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    if (disconnectedSince == 0L) disconnectedSince = System.currentTimeMillis()
                    // STUN/집 Wi-Fi 에서 수 초 흔들림 허용
                    if (System.currentTimeMillis() - disconnectedSince > 15_000L) {
                        Log.i(TAG, "disconnected >15s, end session")
                        return
                    }
                }
                PeerConnection.PeerConnectionState.CONNECTED,
                PeerConnection.PeerConnectionState.CONNECTING -> {
                    disconnectedSince = 0L
                }
                else -> {}
            }
            withTimeoutOrNull(2000) { closed.await() }
        }
    }

    private fun closeConnection() {
        runCatching { dataChannel?.close() }
        runCatching { pc?.close() }
        dataChannel = null; pc = null
    }

    private suspend fun postOffer(offerId: String, sdp: String) {
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("offerId", offerId)
            .put("sdp", sdp)
            .put("mode", "app") // 뷰어: 앱 모드면 L/C/R 위치 버튼 숨김
            .toString()
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
                postOffer(offerId, if (internetPath) filterJunkCandidates(d) else filterToLocalCandidates(d))
                lastRepost = now
            }
            // answer 폴링 빠르게 (워커 KV 무료 한도 대비 초반만 촘촘)
            delay(if (now - start < 15_000) 300 else 1_000)
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
