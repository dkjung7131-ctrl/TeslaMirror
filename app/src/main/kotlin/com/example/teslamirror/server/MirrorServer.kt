package com.example.teslamirror.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 앱(H.264) 모드 전용 내장 Ktor 서버 (AppCastService에서 사용).
 *
 *   - GET /   → WebCodecs 뷰어 HTML
 *   - WS  /ws → H.264 패킷 하향(config/key/delta) + 입력 이벤트 상향(JSON)
 *
 * 전체화면 모드는 로컬 서버가 아니라 WebRTC 데이터채널(WebRtcSession)로 전환됨.
 */
class MirrorServer(
    private val port: Int,
    private val videoWidth: Int = 0,
    private val videoHeight: Int = 0,
    private val onInput: ((String) -> Unit)? = null,
    private val onViewerConnected: (() -> Unit)? = null,
) {

    private var engine: ApplicationEngine? = null

    // --- H.264 ---
    // 프레이밍: byte[0] = 0(config) | 1(key) | 2(delta), 이후 Annex-B 페이로드
    private class H264Client(val ch: Channel<ByteArray>)
    private val h264Clients = CopyOnWriteArrayList<H264Client>()
    @Volatile private var lastConfig: ByteArray? = null

    fun broadcastH264Config(payload: ByteArray) {
        lastConfig = frame(0, payload)
        val f = lastConfig!!
        for (c in h264Clients) c.ch.trySend(f)
    }

    fun broadcastH264Frame(payload: ByteArray, isKey: Boolean) {
        val f = frame(if (isKey) 1 else 2, payload)
        for (c in h264Clients) {
            // 순서 보존이 중요(델타 의존). 밀리면 통째로 닫아 재접속 유도.
            if (c.ch.trySend(f).isFailure) runCatching { c.ch.close() }
        }
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 1)
        out[0] = type.toByte()
        System.arraycopy(payload, 0, out, 1, payload.size)
        return out
    }

    fun start() {
        engine = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(WebSockets)
            routing {
                get("/") { call.respondText(AppViewerHtml.HTML, ContentType.Text.Html) }
                webSocket("/ws") { serveH264(this) }
            }
        }.also { it.start(wait = false) }
    }

    private suspend fun serveH264(session: DefaultWebSocketServerSession) {
        val client = H264Client(Channel(512))
        // 접속 즉시 영상 크기(JSON) → 코덱 설정 전송, 그리고 키프레임 강제 요청
        session.send(Frame.Text("""{"w":$videoWidth,"h":$videoHeight}"""))
        lastConfig?.let { client.ch.trySend(it) }
        h264Clients.add(client)
        onViewerConnected?.invoke()
        try {
            // 상향(입력) 수신 코루틴
            val reader = session.launch {
                try {
                    for (frame in session.incoming) {
                        if (frame is Frame.Text) onInput?.invoke(frame.readText())
                    }
                } catch (_: Throwable) {}
            }
            // 하향(영상) 송신
            for (packet in client.ch) {
                session.send(Frame.Binary(true, packet))
            }
            reader.cancel()
        } catch (_: Throwable) {
        } finally {
            h264Clients.remove(client)
            runCatching { client.ch.close() }
        }
    }

    fun stop() {
        runCatching { engine?.stop(500, 1500) }
        engine = null
        h264Clients.forEach { runCatching { it.ch.close() } }
        h264Clients.clear()
        lastConfig = null
    }
}
