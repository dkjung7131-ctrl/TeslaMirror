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
        check(adb.ensureConnected(context)) { "ADB 연결 실패 — 무선 디버깅/페어링을 확인하세요" }

        // 1) 서버 푸시
        val jar = context.assets.open(ASSET_NAME).use { it.readBytes() }
        adb.pushFile(jar, REMOTE_JAR)

        // 2) 서버 구동 + 가상 디스플레이에 앱 1회 기동.
        // - start_app= 옵션은 이 scrcpy-server 빌드에 없음(Unknown option → 즉시 종료 → UI 깜빡).
        // - am force-stop 은 본화면 앱까지 죽여 깜빡임 → 쓰지 않음.
        // - "New display" 로그 1회만 am start --display (libadb는 shell 동시 2개 불가 → 같은 파이프).
        val scid = String.format(Locale.US, "%08x", Random.nextInt(1, Int.MAX_VALUE))
        val socketName = "localabstract:scrcpy_$scid"
        runCatching {
            adb.runCommand(
                "settings put global force_resizable_activities 1; " +
                    "settings put global enable_freeform_support 1"
            )
        }
        val server = "CLASSPATH=$REMOTE_JAR app_process / com.genymobile.scrcpy.Server $SERVER_VERSION" +
            " scid=$scid log_level=info tunnel_forward=true audio=false control=true video=true" +
            " new_display=${displayWidth}x${displayHeight}/$dpi video_codec=h264 max_fps=$maxFps"
        // Android 16: --activity-new-task / --activity-clear-task 는 미지원(Unknown option).
        // -f 0x10008000 = NEW_TASK|CLEAR_TASK. force-stop 없이 가상 디스플레이에만 1회 기동.
        val cmd = "comp=\$(cmd package resolve-activity --brief $targetPackage 2>/dev/null | awk '/\\// {print; exit}'); " +
            "if [ -z \"\$comp\" ]; then comp=\$(cmd package resolve-activity --brief $targetPackage 2>/dev/null | tail -1); fi; " +
            "echo \"COMP=\$comp PKG=$targetPackage\"; " +
            "started=0; $server 2>&1 | while IFS= read -r line; do echo \"\$line\"; " +
            "case \"\$line\" in *\"New display:\"*) " +
            "if [ \"\$started\" = 0 ]; then started=1; " +
            "id=\$(echo \"\$line\" | grep -o 'id=[0-9]*' | cut -d= -f2); " +
            "echo \"AMSTART[\$id,\$comp]:\$(am start --display \"\$id\" -n \"\$comp\" -f 0x10008000 2>&1)\"; " +
            "fi ;; esac; done"
        Log.i(TAG, "start server scid=$scid pkg=$targetPackage")
        serverStream = adb.openStream("shell:$cmd")

        logThread = Thread { readServerLog(adb, serverStream!!.openInputStream()) }.apply {
            isDaemon = true; start()
        }

        // 3) 영상 소켓 연결 + 파싱 (localabstract — 서버 shell과 공존 OK)
        videoStream = connectWithRetry(adb, socketName)
        videoThread = Thread { readVideo(videoStream!!.openInputStream()) }.apply {
            isDaemon = true; start()
        }

        // 4) 컨트롤 소켓 연결(터치/키 역주입)
        controlStream = connectWithRetry(adb, socketName)
        controlOut = controlStream!!.openOutputStream()
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
            input.bufferedReader().forEachLine { line -> Log.i(TAG, "[server] $line") }
        } catch (_: Throwable) { /* stream closed */ }
    }


    private fun readVideo(input: InputStream) {
        try {
            // 스트림 메타(실측 덤프로 확정): dummy(1) + deviceName(64) + codecId(4)
            //   + codecMeta(12: reserved4 + width4 + height4).
            skipFully(input, 1)
            skipFully(input, 64)
            val codec = ByteArray(4).also { readFully(input, it, 4) }
            val meta = ByteArray(12).also { readFully(input, it, 12) }
            val w = ((meta[4].toInt() and 0xFF) shl 24) or ((meta[5].toInt() and 0xFF) shl 16) or
                ((meta[6].toInt() and 0xFF) shl 8) or (meta[7].toInt() and 0xFF)
            val h = ((meta[8].toInt() and 0xFF) shl 24) or ((meta[9].toInt() and 0xFF) shl 16) or
                ((meta[10].toInt() and 0xFF) shl 8) or (meta[11].toInt() and 0xFF)
            Log.i(TAG, "video codec=${String(codec, Charsets.US_ASCII)} ${w}x$h")

            val header = ByteArray(12)
            var pktNo = 0
            while (running) {
                readFully(input, header, 12)
                val isConfig = (header[0].toInt() and 0x80) != 0
                val isKey = (header[0].toInt() and 0x40) != 0
                val size = ((header[8].toInt() and 0xFF) shl 24) or
                    ((header[9].toInt() and 0xFF) shl 16) or
                    ((header[10].toInt() and 0xFF) shl 8) or
                    (header[11].toInt() and 0xFF)
                if (pktNo < 4) {
                    Log.i(TAG, "PKT#$pktNo header=${header.joinToString(""){ "%02x".format(it) }} cfg=$isConfig key=$isKey size=$size")
                }
                if (size <= 0 || size > 20_000_000) throw IllegalStateException("bad packet size $size (pkt#$pktNo)")
                val payload = ByteArray(size)
                readFully(input, payload, size)
                if (pktNo < 4) {
                    val n = minOf(12, payload.size)
                    Log.i(TAG, "PKT#$pktNo payload[0..$n]=${payload.copyOf(n).joinToString(""){ "%02x".format(it) }}")
                }
                pktNo++
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
