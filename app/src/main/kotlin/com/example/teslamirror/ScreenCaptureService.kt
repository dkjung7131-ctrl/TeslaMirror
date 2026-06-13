package com.example.teslamirror

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.net.NetworkInterface
import androidx.core.app.NotificationCompat
import com.example.teslamirror.capture.MjpegCapturer
import com.example.teslamirror.server.MirrorServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "ScreenCaptureService"
        const val NOTIF_CHANNEL = "tesla_mirror"
        const val NOTIF_ID = 1001

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_FPS = "fps"
        private const val ACTION_STOP = "stop"

        private const val AUTO_STOP_MS = 6 * 60 * 60 * 1000L  // 6시간
        private const val NET_CHECK_INTERVAL_MS = 5_000L      // 5초마다 핫스팟 점검
        // 첫 시작 후 핫스팟이 잡힐 시간 여유
        private const val NET_CHECK_INITIAL_DELAY_MS = 10_000L

        private const val ACTION_WIFI_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        // 10=DISABLING, 11=DISABLED, 12=ENABLING, 13=ENABLED, 14=FAILED
        private val HOTSPOT_OFF_STATES = setOf(10, 11, 14)

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()
        val isRunning: Boolean get() = _isRunningFlow.value

        fun start(context: Context, resultCode: Int, data: Intent, fps: Int) {
            val i = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_FPS, fps)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mjpegCapturer: MjpegCapturer? = null
    private var server: MirrorServer? = null

    // 안전장치: 6시간 강제 종료 + 네트워크 끊김 감지
    private val mainHandler = Handler(Looper.getMainLooper())
    private var consecutiveNetFailures = 0

    private val autoStopRunnable = Runnable {
        Log.i(TAG, "Auto-stop after 6 hours")
        stopEverything()
        stopSelf()
    }

    private val networkCheckRunnable = object : Runnable {
        override fun run() {
            if (isHotspotEnabled(this@ScreenCaptureService)) {
                consecutiveNetFailures = 0
            } else {
                consecutiveNetFailures++
                if (consecutiveNetFailures >= 2) {
                    Log.i(TAG, "Hotspot off (poll), stopping")
                    stopEverything()
                    stopSelf()
                    return
                }
            }
            mainHandler.postDelayed(this, NET_CHECK_INTERVAL_MS)
        }
    }

    // 즉시 반응: 안드로이드의 WIFI_AP_STATE_CHANGED 브로드캐스트
    private val hotspotStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_WIFI_AP_STATE) return
            val state = intent.getIntExtra("wifi_state", -1)
            if (state in HOTSPOT_OFF_STATES) {
                Log.i(TAG, "Hotspot off (broadcast), stopping")
                stopEverything()
                stopSelf()
            }
        }
    }
    private var hotspotReceiverRegistered = false


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)

        val fps = intent?.getIntExtra(EXTRA_FPS, 30) ?: 30

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Invalid projection grant")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startProjection(resultCode, data, fps)
            _isRunningFlow.value = true
            mainHandler.postDelayed(autoStopRunnable, AUTO_STOP_MS)
            consecutiveNetFailures = 0
            mainHandler.postDelayed(networkCheckRunnable, NET_CHECK_INITIAL_DELAY_MS)
            registerHotspotReceiver()
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            stopEverything()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent, fps: Int) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopEverything(); stopSelf() }
            }, null)
        }

        val (w, h, dpi) = displayParams()
        val (capW, capH) = scaleTo720p(w, h)

        val server = MirrorServer(HttpConfig.PORT).also { this.server = it }
        server.start()

        val cap = MjpegCapturer(capW, capH, fps = fps, quality = 65) { jpegBytes ->
            server.broadcastMjpeg(jpegBytes)
        }
        mjpegCapturer = cap
        virtualDisplay = projection!!.createVirtualDisplay(
            "TeslaMirror-MJPEG",
            capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            cap.surface, null, null
        )
        cap.start(scope)
    }

    private fun displayParams(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun scaleTo720p(w: Int, h: Int): Pair<Int, Int> {
        val target = 720
        val short = minOf(w, h)
        if (short <= target) return w to h
        val ratio = target.toFloat() / short
        val newW = (w * ratio).toInt() and 0xFFFFFFFE.toInt()
        val newH = (h * ratio).toInt() and 0xFFFFFFFE.toInt()
        return newW to newH
    }

    private fun stopEverything() {
        _isRunningFlow.value = false
        mainHandler.removeCallbacks(autoStopRunnable)
        mainHandler.removeCallbacks(networkCheckRunnable)
        unregisterHotspotReceiver()
        try { mjpegCapturer?.stop() } catch (_: Throwable) {}
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        try { server?.stop() } catch (_: Throwable) {}
        mjpegCapturer = null
        virtualDisplay = null; projection = null; server = null
        scope.cancel()
    }

    private fun registerHotspotReceiver() {
        if (hotspotReceiverRegistered) return
        try {
            val filter = IntentFilter(ACTION_WIFI_AP_STATE)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(hotspotStateReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(hotspotStateReceiver, filter)
            }
            hotspotReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Hotspot receiver register failed", t)
        }
    }

    private fun unregisterHotspotReceiver() {
        if (!hotspotReceiverRegistered) return
        try { unregisterReceiver(hotspotStateReceiver) } catch (_: Throwable) {}
        hotspotReceiverRegistered = false
    }

    private fun startInForeground() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("TeslaMirror 동작 중")
            .setContentText("테슬라 브라우저에서 폰 IP로 접속하세요")
            .setSmallIcon(R.drawable.ic_notification_mirror)
            .setOngoing(true)
            .addAction(0, "중지", stopPi)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannelIfNeeded() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "TeslaMirror", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopEverything()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
}

/**
 * 폰의 핫스팟(테더링)이 켜져 있는지 검사.
 * 1차: WifiManager.isWifiApEnabled() 리플렉션 (안드로이드 hidden API)
 * 2차 (리플렉션 막힌 경우): ap0/softap0/swlan0 같은 핫스팟 인터페이스의 IPv4 존재 여부
 */
fun isHotspotEnabled(context: Context): Boolean {
    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val reflectionResult: Boolean? = try {
        val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
        method.isAccessible = true
        method.invoke(wm) as? Boolean
    } catch (_: Throwable) { null }

    val interfaceResult: Boolean = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { ni ->
                val name = ni.name.lowercase()
                name.startsWith("ap") || name.startsWith("softap") ||
                    name.startsWith("swlan") || name == "wlan1"
            }
            .flatMap { it.inetAddresses.toList() }
            .any { !it.isLinkLocalAddress && it.hostAddress?.contains(':') == false }
    } catch (_: Throwable) { false }

    // 둘 중 하나라도 "꺼짐"이라고 하면 꺼진 것으로 판단 (안전 우선).
    return when {
        reflectionResult == false -> false
        reflectionResult == true && !interfaceResult -> false  // 리플렉션이 거짓말하는 경우 대비
        reflectionResult == null -> interfaceResult
        else -> interfaceResult  // both true
    }
}
