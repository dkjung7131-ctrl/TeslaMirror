package com.example.teslamirror.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Embedded Ktor server that serves:
 *   - GET /         → viewer HTML
 *   - GET /stream   → MJPEG multipart/x-mixed-replace stream
 */
class MirrorServer(private val port: Int) {

    private var engine: ApplicationEngine? = null

    private data class MjpegClient(val ch: Channel<ByteArray>)
    private val mjpegClients = CopyOnWriteArrayList<MjpegClient>()

    fun broadcastMjpeg(jpeg: ByteArray) {
        // CONFLATED 채널이라 trySend가 자동으로 옛 값을 교체함
        for (c in mjpegClients) c.ch.trySend(jpeg)
    }

    fun start() {
        engine = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            routing {
                get("/") {
                    call.respondText(ViewerHtml.HTML, ContentType.Text.Html)
                }

                get("/stream") {
                    val boundary = "tmboundary"
                    call.response.header("Cache-Control", "no-cache, private")
                    call.response.header("Pragma", "no-cache")
                    call.response.header("Connection", "close")
                    val client = MjpegClient(Channel(Channel.CONFLATED))
                    mjpegClients.add(client)
                    try {
                        call.respondBytesWriter(
                            contentType = ContentType.parse("multipart/x-mixed-replace; boundary=$boundary")
                        ) {
                            writeStringUtf8("--$boundary\r\n")
                            while (!isClosedForWrite) {
                                val jpeg = client.ch.receive()
                                writeStringUtf8(
                                    "Content-Type: image/jpeg\r\n" +
                                    "Content-Length: ${jpeg.size}\r\n\r\n"
                                )
                                writeFully(jpeg)
                                writeStringUtf8("\r\n--$boundary\r\n")
                                flush()
                            }
                        }
                    } catch (_: Throwable) { /* client gone */ }
                    finally { mjpegClients.remove(client) }
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        runCatching { engine?.stop(500, 1500) }
        engine = null
        mjpegClients.forEach { runCatching { it.ch.close() } }
        mjpegClients.clear()
    }
}
