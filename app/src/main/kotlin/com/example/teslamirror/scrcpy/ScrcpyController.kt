package com.example.teslamirror.scrcpy

import android.content.Context
import android.util.Log
import com.example.teslamirror.adb.AdbManager
import io.github.muntashirakon.adb.AdbStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.random.Random

/**
 * scrcpy-server를 폰의 adbd(내장 ADB)로 구동하고, 헤드리스 가상 디스플레이의
 * H.264 스트림을 받아 콜백으로 넘긴다. 컨트롤(터치/키)은 역방향으로 주입한다.
 *
 * 흐름:
 *  1. assets/scrcpy-server.jar → /data/local/tmp 푸시
 *  2. shell로 서버 구동 (tunnel_forward, new_display=WxH, control=true)
 *  3. localabstract 소켓 연결: 1번째=영상, 2번째=컨트롤
 *  4. 서버 로그의 "New display: ...(id=N)" 파싱 → am start로 대상 앱 실행
 *  5. 영상 패킷(config/key/delta) 파싱해 콜백
 */
class ScrcpyController(
    private val context: Context,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val dpi: Int,
    private val maxFps: Int,
    private val targetPackage: String,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (ByteArray, Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    val videoWidth get() = displayWidth
    val videoHeight get() = displayHeight

    @Volatile private var running = false
    private var serverStream: AdbStream? = null
    private var videoStream: AdbStream? = null
    private var controlStream: AdbStream? = null
    private var controlOut: OutputStream? = null
    private val controlLock = Any()

    private var logThread: Thread? = null
    private var videoThread: Thread? = null

    fun start() {
        running = true
        val adb = AdbManager.getInstance(context)
        if (!adb.isConnected) {
            // 무선 디버깅이 켜져 있으면 mDNS로 자기 adbd 자동 탐색 (페어링은 사전 완료 가정)
            check(adb.autoConnect(context, 10_000)) { "ADB 자동 연결 실패 — 무선 디버깅/페어링을 확인하세요" }
        }

        // 1) 서버 푸시
        val jar = context.assets.open(ASSET_NAME).use { it.readBytes() }
        adb.pushFile(jar, REMOTE_JAR)

        // 2) 서버 구동
        val scid = String.format(Locale.US, "%08x", Random.nextInt(1, Int.MAX_VALUE))
        val socketName = "localabstract:scrcpy_$scid"
        val cmd = buildString {
            append("CLASSPATH=$REMOTE_JAR app_process / com.genymobile.scrcpy.Server $SERVER_VERSION")
            append(" scid=$scid log_level=info tunnel_forward=true")
            append(" audio=false control=true video=true")
            append(" new_display=${displayWidth}x${displayHeight}/$dpi")
            append(" video_codec=h264 max_fps=$maxFps")
        }
        serverStream = adb.openStream("shell:$cmd")

        // 서버 로그 감시: New display id 파싱 → 앱 실행
        logThread = Thread { readServerLog(adb, serverStream!!.openInputStream()) }.apply {
            isDaemon = true; start()
        }

        // 3) 소켓 연결 (순서: 영상 → 컨트롤)
        videoStream = connectWithRetry(adb, socketName)
        controlStream = connectWithRetry(adb, socketName)
        controlOut = controlStream!!.openOutputStream()

        // 5) 영상 파싱 루프
        videoThread = Thread { readVideo(videoStream!!.openInputStream()) }.apply {
            isDaemon = true; start()
        }
    }

    fun sendControl(bytes: ByteArray) {
        val out = controlOut ?: return
        synchronized(controlLock) {
            try {
                out.write(bytes)
                out.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "control write failed", t)
            }
        }
    }

    fun stop() {
        running = false
        runCatching { videoStream?.close() }
        runCatching { controlStream?.close() }
        runCatching { serverStream?.close() }  // 셸 종료 → 서버 프로세스 종료
        videoStream = null; controlStream = null; serverStream = null; controlOut = null
    }

    private fun connectWithRetry(adb: AdbManager, service: String): AdbStream {
        var lastErr: Throwable? = null
        repeat(40) {
            if (!running) throw IllegalStateException("stopped")
            try {
                return adb.openStream(service)
            } catch (t: Throwable) {
                lastErr = t
                Thread.sleep(100)
            }
        }
        throw IllegalStateException("scrcpy 소켓 연결 실패: $service", lastErr)
    }

    private fun readServerLog(adb: AdbManager, input: InputStream) {
        try {
            input.bufferedReader().forEachLine { line ->
                Log.i(TAG, "[server] $line")
                if (running && line.contains("New display:")) {
                    val id = Regex("""id=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    if (id != null) launchApp(adb, id)
                }
            }
        } catch (_: Throwable) { /* stream closed */ }
    }

    private fun launchApp(adb: AdbManager, displayId: Int) {
        try {
            val resolved = adb.runCommand("cmd package resolve-activity --brief $targetPackage")
                .trim().lines().map { it.trim() }.lastOrNull { it.contains("/") }
            if (resolved.isNullOrBlank()) {
                onError("앱 실행 실패: $targetPackage 액티비티를 찾을 수 없음")
                return
            }
            adb.runCommand("am start --display $displayId -n $resolved")
            Log.i(TAG, "launched $resolved on display $displayId")
        } catch (t: Throwable) {
            onError("앱 실행 실패: ${t.message}")
        }
    }

    private fun readVideo(input: InputStream) {
        try {
            // 스트림 메타: dummy(1) + deviceName(64) + codecId(4)
            skipFully(input, 1)
            skipFully(input, 64)
            val codec = ByteArray(4).also { readFully(input, it, 4) }
            Log.i(TAG, "video codec=${String(codec, Charsets.US_ASCII)}")

            val header = ByteArray(12)
            while (running) {
                readFully(input, header, 12)
                val isConfig = (header[0].toInt() and 0x80) != 0
                val isKey = (header[0].toInt() and 0x40) != 0
                val size = ((header[8].toInt() and 0xFF) shl 24) or
                    ((header[9].toInt() and 0xFF) shl 16) or
                    ((header[10].toInt() and 0xFF) shl 8) or
                    (header[11].toInt() and 0xFF)
                if (size <= 0 || size > 20_000_000) throw IllegalStateException("bad packet size $size")
                val payload = ByteArray(size)
                readFully(input, payload, size)
                if (isConfig) onConfig(payload) else onFrame(payload, isKey)
            }
        } catch (t: Throwable) {
            if (running) onError("영상 스트림 종료: ${t.message}")
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    private fun skipFully(input: InputStream, len: Int) {
        val tmp = ByteArray(len)
        readFully(input, tmp, len)
    }

    companion object {
        private const val TAG = "ScrcpyController"
        private const val SERVER_VERSION = "4.1"
        private const val ASSET_NAME = "scrcpy-server.jar"
        private const val REMOTE_JAR = "/data/local/tmp/scrcpy-server.jar"
    }
}
