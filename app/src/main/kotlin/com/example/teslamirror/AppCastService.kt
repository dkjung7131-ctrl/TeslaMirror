package com.example.teslamirror

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.teslamirror.capture.H264ToJpeg
import com.example.teslamirror.rendezvous.RendezvousUpdater
import com.example.teslamirror.scrcpy.ScrcpyController
import com.example.teslamirror.scrcpy.ScrcpyProtocol
import com.example.teslamirror.webrtc.AppWebRtcSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 앱 전용(헤드리스) 미러링 서비스.
 *
 * MediaProjection 대신 내장 ADB로 scrcpy-server를 구동해, 선택한 앱을
 * 테슬라 해상도 가상 디스플레이에서 렌더링하고 H.264로 재중계한다.
 * 폰 화면은 자유로워지고, 테슬라 브라우저 터치로 그 앱을 조작한다.
 */
class AppCastService : Service() {

    companion object {
        private const val TAG = "AppCastService"
        private const val NOTIF_CHANNEL = "tesla_mirror_app"
        private const val NOTIF_ID = 1002
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_INTERNET_PATH = "internet_path"
        private const val ACTION_STOP = "stop"

        // 테슬라 브라우저에 맞춘 가로 해상도(대략). 뷰어가 contain으로 맞추므로 정밀하지 않아도 됨.
        const val DISPLAY_WIDTH = 1280
        const val DISPLAY_HEIGHT = 800
        const val DISPLAY_DPI = 200
        const val MAX_FPS = 30

        private const val ACTION_WIFI_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private val HOTSPOT_OFF_STATES = setOf(10, 11, 14)

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        private val _statusFlow = MutableStateFlow("")
        val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()

        fun start(context: Context, packageName: String, internetPath: Boolean = true) {
            val i = Intent(context, AppCastService::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_INTERNET_PATH, internetPath)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, AppCastService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }

    private var controller: ScrcpyController? = null
    private var webrtc: AppWebRtcSession? = null
    private var decoder: H264ToJpeg? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_WIFI_AP_STATE) return
            if (intent.getIntExtra("wifi_state", -1) in HOTSPOT_OFF_STATES) {
                Log.i(TAG, "hotspot off, stopping")
                stopEverything(); stopSelf()
            }
        }
    }
    private var receiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything(); stopSelf(); return START_NOT_STICKY
        }
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)
        if (pkg.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
        val internetPath = intent.getBooleanExtra(EXTRA_INTERNET_PATH, true)

        // 전체화면과 동일하게 시그널링(워커)에 시크릿 필요.
        if (!RendezvousUpdater.isConfigured(this)) {
            _statusFlow.value = "공용 접속 주소 시크릿을 먼저 저장하세요"
            stopSelf(); return START_NOT_STICKY
        }

        startInForeground()
        _statusFlow.value = "시작 중…"

        // 전송: 전체화면과 같은 워커+STUN+JPEG 데이터채널 (전체화면 코드는 안 건드림).
        val rtc = AppWebRtcSession(
            context = this,
            onStatus = { _statusFlow.value = it },
            onViewerMessage = { json -> handleInput(json) },   // Phase 2 터치 역제어
            onConnected = { controller?.sendControl(ScrcpyProtocol.resetVideo()) }, // 새 뷰어에 키프레임
            internetPath = internetPath,                        // ON=테슬라(공인 ICE), OFF=로컬 host(데스크)
        ).also { webrtc = it }

        // scrcpy H.264 → 디코드 → JPEG → 데이터채널. 뷰어가 못 받을 땐 인코딩 스킵.
        val dec = H264ToJpeg(
            width = DISPLAY_WIDTH,
            height = DISPLAY_HEIGHT,
            shouldEncode = { rtc.canSend() },
            onJpeg = { jpeg -> rtc.pushJpeg(jpeg) },
        ).also { decoder = it }

        val ctrl = ScrcpyController(
            context = this,
            displayWidth = DISPLAY_WIDTH,
            displayHeight = DISPLAY_HEIGHT,
            dpi = DISPLAY_DPI,
            maxFps = MAX_FPS,
            targetPackage = pkg,
            onConfig = { cfg -> dec.submitConfig(cfg) },
            onFrame = { data, key -> dec.submitFrame(data, key) },
            onError = { msg -> Log.w(TAG, msg); _statusFlow.value = msg },
        ).also { controller = it }

        // 네트워크/ADB 작업은 별도 스레드에서
        Thread {
            try {
                rtc.start()
                ctrl.start()
                _isRunningFlow.value = true
                _statusFlow.value = "실행 중 — 테슬라에서 접속하세요"
                registerReceiverSafely()
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                _statusFlow.value = "시작 실패: ${t.message}"
                stopEverything(); stopSelf()
            }
        }.apply { isDaemon = true }.start()

        return START_NOT_STICKY
    }

    private fun handleInput(json: String) {
        val c = controller ?: return
        try {
            val o = JSONObject(json)
            when (o.getString("t")) {
                "touch" -> {
                    val a = when (o.getInt("a")) {
                        0 -> ScrcpyProtocol.ACTION_DOWN
                        1 -> ScrcpyProtocol.ACTION_UP
                        else -> ScrcpyProtocol.ACTION_MOVE
                    }
                    c.sendControl(
                        ScrcpyProtocol.injectTouch(a, 0L, o.getInt("x"), o.getInt("y"), c.videoWidth, c.videoHeight)
                    )
                }
                "key" -> {
                    val a = if (o.getInt("a") == 0) ScrcpyProtocol.KEY_DOWN else ScrcpyProtocol.KEY_UP
                    c.sendControl(ScrcpyProtocol.injectKeycode(a, o.getInt("code"), 0, 0))
                }
                "text" -> c.sendControl(ScrcpyProtocol.injectText(o.getString("s")))
                "back" -> {
                    c.sendControl(ScrcpyProtocol.backOrScreenOn(ScrcpyProtocol.KEY_DOWN))
                    c.sendControl(ScrcpyProtocol.backOrScreenOn(ScrcpyProtocol.KEY_UP))
                }
            }
        } catch (_: Throwable) {}
    }

    private fun stopEverything() {
        _isRunningFlow.value = false
        _statusFlow.value = ""
        unregisterReceiverSafely()
        runCatching { controller?.stop() }
        runCatching { decoder?.stop() }
        runCatching { webrtc?.stop() }
        controller = null; decoder = null; webrtc = null
    }

    private fun registerReceiverSafely() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter(ACTION_WIFI_AP_STATE)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(hotspotReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(hotspotReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Throwable) {}
    }

    private fun unregisterReceiverSafely() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(hotspotReceiver) }
        receiverRegistered = false
    }

    private fun startInForeground() {
        val stopIntent = Intent(this, AppCastService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("TeslaMirror 앱 캐스트")
            .setContentText("테슬라 브라우저에서 접속하세요")
            .setSmallIcon(R.drawable.ic_notification_mirror)
            .setOngoing(true)
            .addAction(0, "중지", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "TeslaMirror 앱 캐스트", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopEverything(); stopSelf(); super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() { stopEverything(); super.onDestroy() }
}
