package com.example.teslamirror.webrtc

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import com.example.teslamirror.capture.MjpegCapturer
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
 * 전체화면 모드 전송 (저지연 우선).
 *
 * JPEG 프레임을 WebRTC 데이터 채널로 흘린다(버퍼/GOP 지연 없음 → 내비 실시간성).
 * 전송은 로컬 핫스팟 P2P 경로를 타고(사설 IP 차단 회피), 캔버스+JPEG라 구형 테슬라
 * 브라우저에서도 동작. 시그널링은 [RendezvousUpdater]의 워커 엔드포인트를 공유한다.
 *
 * 라이프사이클: negotiateLoop 한 번에 PeerConnection 하나. 협상 상태는 연결마다
 * 새로 만든 [CompletableDeferred]로 대기하므로 "먼저 완료된 이벤트"도 안전하게 받는다.
 */
class WebRtcSession(
    private val context: Context,
    private val resultCode: Int,
    private val projectionData: Intent,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val onStatus: (String) -> Unit,
    private val onProjectionLost: () -> Unit,
    // 널이 아니면 VPN 게이트웨이 모드: offer의 사설 host 후보 IP를 이 가짜 공인 IP로 재작성.
    // (데스크 실측: 순수 VpnService 가로채기는 S936N에서 실패 — 연구/프로브용으로만 유지.)
    private val advertiseIp: String? = null,
    // true면 STUN으로 공인 후보를 모으고 사설 후보를 버린다. 테슬라 LAN 격리 우회용
    // (영상은 인터넷/셀룰러 경로 — 지연↑, 비루트 로컬 가로채기 불가로 전환).
    private val internetPath: Boolean = false,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var factory: PeerConnectionFactory
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var capturer: MjpegCapturer? = null
    private var pc: PeerConnection? = null
    @Volatile private var dataChannel: DataChannel? = null
    @Volatile private var running = false

    // 현재 연결의 협상 신호 (createPeerConnection에서 새로 만든다)
    private var gathered = CompletableDeferred<Unit>()
    private var connected = CompletableDeferred<Unit>()
    private var closed = CompletableDeferred<Unit>()

    private val deviceId get() = RendezvousUpdater.deviceId(context)

    fun start() {
        running = true
        Log.i(TAG, "start deviceId=$deviceId ${width}x$height@$fps")
        initFactory()
        startCapture()
        scope.launch { negotiateLoop() }
    }

    fun stop() {
        running = false
        closeConnection()
        runCatching { capturer?.stop() }
        runCatching { virtualDisplay?.release() }
        runCatching { projection?.stop() }
        capturer = null; virtualDisplay = null; projection = null
        scope.cancel()                       // negotiateLoop 종료
        // factory.dispose() 금지 — closeConnection과 같은 SIGILL 계열 위험(네이티브 해제).
        // v0.5.2까지 해제 없이 안정 동작 확인. 세션 종료 후 프로세스가 놀면 OS가 회수한다.
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
        // VPN 게이트웨이 모드는 미검증이라, 실차 진단용으로 libwebrtc ICE 로그를 켠다
        // (connection.cc의 STUN 송수신 확인 → 연결 실패 원인 즉시 파악). 검증 후 낮춰도 됨.
        if (advertiseIp != null) {
            runCatching {
                org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_INFO)
            }
        }
    }

    private fun startCapture() {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, projectionData).also { projection = it }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
                if (running) { running = false; onProjectionLost() }
            }
        }, null)
        // 인코딩은 보낼 수 있을 때만 (뷰어 연결 + 버퍼 여유). 아니면 JPEG 압축 자체를 건너뜀.
        val cap = MjpegCapturer(width, height, fps = fps, quality = 60, shouldEncode = { canSend() }) { jpeg ->
            sendFrame(jpeg)
        }
        capturer = cap
        virtualDisplay = proj.createVirtualDisplay(
            "TeslaMirror", width, height, context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            cap.surface, null, null
        )
        cap.start(scope)
    }

    private fun canSend(): Boolean {
        val dc = dataChannel ?: return false
        return dc.state() == DataChannel.State.OPEN && dc.bufferedAmount() <= BUFFER_LIMIT
    }

    private fun sendFrame(jpeg: ByteArray) {
        val dc = dataChannel ?: return
        try {
            if (dc.state() != DataChannel.State.OPEN) return
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
                postOffer(offerId, createOfferAndGather())
                Log.i(TAG, "offer posted id=$offerId")
                val answer = awaitAnswer(offerId)
                if (answer == null) { closeConnection(); continue }
                setRemote(answer)
                val waitMs = if (internetPath) 18_000L else 12_000L
                onStatus("ICE 연결 중…")
                val connectedOk = withTimeoutOrNull(waitMs) { connected.await() } != null
                if (!connectedOk) {
                    Log.i(TAG, "not connected in ${waitMs}ms, re-offering state=${pc?.connectionState()}")
                    closeConnection(); continue
                }
                onStatus("연결됨")
                awaitClosed()
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "negotiate error", t)
            } finally {
                closeConnection()
            }
            if (running) delay(800)
        }
    }

    private fun createPeerConnection() {
        gathered = CompletableDeferred()
        connected = CompletableDeferred()
        closed = CompletableDeferred()
        val g = gathered; val c = connected; val x = closed   // 이 연결 전용 캡처
        val iceServers = if (internetPath || advertiseIp != null) {
            // 인터넷/VPN 광고 모드: STUN으로 공인 후보 확보 (테슬라는 사설 IP로 UDP 안 냄)
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            )
        } else {
            emptyList() // PC 핫스팟 로컬 테스트: host only
        }
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            if (internetPath || advertiseIp != null) iceCandidatePoolSize = 4
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
                        if (!c.isCompleted) c.complete(Unit)
                    }
                    // DISCONNECTED 는 일시 ICE 끊김 — 즉시 세션 종료하면 연결중/재연결 깜빡임
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
                if (st == DataChannel.State.CLOSED || st == DataChannel.State.CLOSING) {
                    if (c.isCompleted && !x.isCompleted) x.complete(Unit)
                }
            }
            override fun onMessage(buffer: org.webrtc.DataChannel.Buffer?) {}
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
        // 유용 후보 모이면 조기 offer (최대 ~1.8s) — 첫 연결 체감 단축
        waitForUsefulIce(needPublic = internetPath || advertiseIp != null)
        val raw = pc!!.localDescription?.description ?: offer.description
        // internetPath: 공인(srflx) + 사설(host) 모두 유지.
        //  - 테슬라(다른 망): 사설 실패 후 공인으로 연결
        //  - 집 Wi-Fi 같은 LAN 노트북: 사설 host로 바로 연결 (공인 hairpin 실패 회피)
        //  - 127.0.0.1 / 0.0.0.0 만 제거
        // 로컬 전용(!internetPath): 사설 host 만 (예전 PC 핫스팟 디버그)
        val filtered = when {
            advertiseIp != null -> filterToLocalCandidates(raw)
            internetPath -> filterJunkCandidates(raw)
            else -> filterToLocalCandidates(raw)
        }
        val hasCand = Regex("""a=candidate:""").containsMatchIn(filtered)
        if (!hasCand) {
            Log.w(TAG, "no candidate after filter internet=$internetPath — fallback raw")
            onStatus(
                if (internetPath) "공인 ICE 후보 없음 — 셀룰러/STUN 확인"
                else "로컬 후보 없음 — 핫스팟 확인"
            )
        }
        val base = if (hasCand) filtered else raw
        val sdp = advertiseIp?.let { rewriteToAdvertiseIp(base, it) } ?: base
        val n = Regex("""a=candidate:""").findAll(sdp).count()
        val priv = Regex("""a=candidate:""").findAll(sdp).count { isPrivateIpv4(it.value.split(' ').getOrNull(4) ?: "") }
        Log.i(TAG, "offer ready cands=$n privateLeft=$priv internet=$internetPath vpn=$advertiseIp")
        return sdp
    }

    private suspend fun waitForUsefulIce(needPublic: Boolean) {
        val deadline = System.currentTimeMillis() + 1_800L
        while (running && System.currentTimeMillis() < deadline) {
            if (gathered.isCompleted) return
            val sdp = pc?.localDescription?.description.orEmpty()
            if (needPublic) {
                if (sdp.contains("typ srflx") || sdp.contains("typ relay")) return
            } else if (Regex("""a=candidate:.*typ host""").containsMatchIn(sdp)) {
                return
            }
            delay(50)
        }
        withTimeoutOrNull(100) { if (!gathered.isCompleted) gathered.await() }
    }

    /** 사설 IPv4 host 후보만 남긴다(로컬 핫스팟 PC 테스트용). */
    private fun filterToLocalCandidates(sdp: String): String {
        val out = sdp.split("\r\n", "\n").map { it.trimEnd('\r') }.filter { line ->
            if (!line.startsWith("a=candidate:")) return@filter true
            val addr = line.removePrefix("a=").split(' ').getOrNull(4) ?: return@filter false
            isPrivateIpv4(addr)
        }
        return out.joinToString("\r\n").trimEnd() + "\r\n"
    }

    /** 사설 IPv4 후보를 제거(구 테슬라-only 검증용, 일부 경로에서 재사용). */
    private fun filterToPublicCandidates(sdp: String): String {
        val out = sdp.split("\r\n", "\n").map { it.trimEnd('\r') }.filter { line ->
            if (!line.startsWith("a=candidate:")) return@filter true
            val addr = line.removePrefix("a=").split(' ').getOrNull(4) ?: return@filter false
            !isPrivateIpv4(addr) && addr != "0.0.0.0" && addr != "127.0.0.1"
        }
        return out.joinToString("\r\n").trimEnd() + "\r\n"
    }

    /** 루프백·무효 주소만 제거. 사설 host + 공인 srflx 동시 유지(집 LAN + 테슬라). */
    private fun filterJunkCandidates(sdp: String): String {
        val out = sdp.split("\r\n", "\n").map { it.trimEnd('\r') }.filter { line ->
            if (!line.startsWith("a=candidate:")) return@filter true
            val addr = line.removePrefix("a=").split(' ').getOrNull(4) ?: return@filter false
            addr != "0.0.0.0" && addr != "127.0.0.1" && !addr.startsWith("192.0.0.")
        }
        return out.joinToString("\r\n").trimEnd() + "\r\n"
    }

    /** 사설 IPv4 후보 주소와 c=IN IP4 연결선을 [ip]로 치환(포트/그 외 유지). */
    private fun rewriteToAdvertiseIp(sdp: String, ip: String): String =
        sdp.split("\r\n", "\n").map { it.trimEnd('\r') }.joinToString("\r\n") { line ->
            when {
                line.startsWith("a=candidate:") -> {
                    val parts = line.removePrefix("a=").split(' ').toMutableList()
                    val addr = parts.getOrNull(4)
                    if (addr != null && isPrivateIpv4(addr)) { parts[4] = ip; "a=" + parts.joinToString(" ") }
                    else line
                }
                line.startsWith("c=IN IP4 ") -> {
                    val addr = line.removePrefix("c=IN IP4 ").trim()
                    if (isPrivateIpv4(addr)) "c=IN IP4 $ip" else line
                }
                else -> line
            }
        }.trimEnd() + "\r\n"

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

    /** set{Local,Remote}Description의 공통 SdpObserver 래퍼. */
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

    /** 연결이 끊길 때까지 대기. 이벤트 유실 대비로 연결 상태를 주기 확인한다. */
    private suspend fun awaitClosed() {
        // DISCONNECTED 는 복구 가능 — 15초 이상일 때만 종료. FAILED/CLOSED 즉시 종료.
        var disconnectedSince = 0L
        while (running) {
            val st = pc?.connectionState() ?: return
            when (st) {
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> return
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    if (disconnectedSince == 0L) disconnectedSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - disconnectedSince > 15_000L) {
                        Log.i(TAG, "disconnected >15s, end session")
                        return
                    }
                }
                PeerConnection.PeerConnectionState.CONNECTED,
                PeerConnection.PeerConnectionState.CONNECTING -> disconnectedSince = 0L
                else -> {}
            }
            withTimeoutOrNull(2000) { closed.await() }
        }
    }

    private fun closeConnection() {
        // dispose()는 금지: 이 조합(stream-webrtc 1.3.8 + Galaxy S936N/Android 16)에서
        // nativeFreeOwnedPeerConnection SIGILL로 앱이 통째로 죽는다(2026-07-17 실측 3회 —
        // 뷰어가 끊겨 재협상하는 순간 사망 → 테슬라가 영원히 "연결중"에 갇히는 원인).
        // close()만 하고 네이티브 해제는 GC에 맡긴다. 재연결당 소량 누수 < 크래시.
        runCatching { dataChannel?.close() }
        runCatching { pc?.close() }
        dataChannel = null; pc = null
    }

    // ---- 시그널링 (RendezvousUpdater 워커 엔드포인트 공유) ----
    private suspend fun postOffer(offerId: String, sdp: String) {
        val body = JSONObject().put("deviceId", deviceId).put("offerId", offerId).put("sdp", sdp).toString()
        runCatching { RendezvousUpdater.postJson(context, "/offer", body) }
    }

    /** 앤서를 폴링. 처음엔 촘촘히, 이후 성기게(무료 한도 절약). TTL 만료 전 오퍼 재게시. */
    private suspend fun awaitAnswer(offerId: String): String? {
        val start = System.currentTimeMillis()
        var lastRepost = start
        while (running) {
            fetchAnswer(offerId)?.let { return it }
            val now = System.currentTimeMillis()
            if (now - lastRepost > 100_000) {   // TTL(120s) 만료 전 재게시
                val d = pc?.localDescription?.description ?: return null
                val f = when {
                    advertiseIp != null -> filterToLocalCandidates(d)
                    internetPath -> filterJunkCandidates(d)
                    else -> filterToLocalCandidates(d)
                }
                postOffer(offerId, advertiseIp?.let { rewriteToAdvertiseIp(f, it) } ?: f)
                lastRepost = now
            }
            delay(if (now - start < 15_000) 300 else 1_000) // 첫 연결 빠르게, 이후 완화
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
        private const val TAG = "WebRtcSession"
        private const val BUFFER_LIMIT = 96 * 1024L  // 데이터채널 버퍼 상한(~1.5프레임)
    }
}
