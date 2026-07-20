package com.example.teslamirror

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
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
import com.example.teslamirror.rendezvous.RendezvousUpdater
import com.example.teslamirror.vpn.GatewayVpnService
import com.example.teslamirror.webrtc.WebRtcSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "ScreenCaptureService"
        const val NOTIF_CHANNEL = "tesla_mirror"
        const val NOTIF_ID = 1001

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_INTERNET_PATH = "internet_path"
        private const val ACTION_STOP = "stop"

        private const val AUTO_STOP_MS = 6 * 60 * 60 * 1000L  // 6시간
        private const val NET_CHECK_INTERVAL_MS = 5_000L      // 5초마다 핫스팟 점검
        // 접선 서버 재등록 주기 — 주행 중 통신사 NAT의 공인 IP가 바뀌어도 따라가게
        private const val REGISTER_INTERVAL_MS = 10 * 60_000L
        // 첫 시작 후 핫스팟이 잡힐 시간 여유
        private const val NET_CHECK_INITIAL_DELAY_MS = 10_000L

        private const val ACTION_WIFI_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        // 10=DISABLING, 11=DISABLED, 12=ENABLING, 13=ENABLED, 14=FAILED
        private val HOTSPOT_OFF_STATES = setOf(10, 11, 14)

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()
        val isRunning: Boolean get() = _isRunningFlow.value

        private val _statusFlow = MutableStateFlow("")
        val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            fps: Int,
            internetPath: Boolean = true,
        ) {
            val i = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_INTERNET_PATH, internetPath)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var webRtcSession: WebRtcSession? = null
    /** true면 공인 ICE — 핫스팟 없어도 유지 (테슬라/집 Wi-Fi 노트북 테스트). */
    private var internetPathActive = true

    // 안전장치: 6시간 강제 종료 + (로컬 경로일 때만) 핫스팟 끊김 감지
    private val mainHandler = Handler(Looper.getMainLooper())
    private var consecutiveNetFailures = 0

    private val autoStopRunnable = Runnable {
        Log.i(TAG, "Auto-stop after 6 hours")
        stopEverything()
        stopSelf()
    }

    private val networkCheckRunnable = object : Runnable {
        override fun run() {
            // 인터넷 ICE는 핫스팟 불필요 — 집 Wi-Fi/셀룰러만으로도 유지
            if (internetPathActive) return
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

    // 로컬 ICE 경로에서만: 핫스팟 꺼지면 즉시 종료
    private val hotspotStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (internetPathActive) return
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
        val internetPath = intent?.getBooleanExtra(EXTRA_INTERNET_PATH, true) ?: true

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Invalid projection grant")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (scope.coroutineContext[kotlinx.coroutines.Job]?.isActive != true) {
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob())  // 재시작 대비 재생성
            }
            internetPathActive = internetPath
            startProjection(resultCode, data, fps, internetPath)
            _isRunningFlow.value = true
            mainHandler.postDelayed(autoStopRunnable, AUTO_STOP_MS)
            consecutiveNetFailures = 0
            // 로컬(사설) ICE만 핫스팟 감시. 인터넷 ICE는 핫스팟 없이 유지.
            if (!internetPath) {
                mainHandler.postDelayed(networkCheckRunnable, NET_CHECK_INITIAL_DELAY_MS)
                registerHotspotReceiver()
            }
            startPeriodicRendezvousRegistration()
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            stopEverything()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(
        resultCode: Int,
        data: Intent,
        fps: Int,
        internetPath: Boolean,
    ) {
        // 시그널링: Cloudflare Worker. 미디어: internetPath면 공인 ICE(테슬라), 아니면 사설 로컬.
        if (!RendezvousUpdater.isConfigured(this)) {
            Log.w(TAG, "rendezvous secret not set — WebRTC signaling unavailable")
            throw IllegalStateException("공용 접속 주소 시크릿을 먼저 저장하세요")
        }
        val (w, h, _) = displayParams()
        val (capW, capH) = scaleTo720p(w, h)
        webRtcSession = WebRtcSession(
            context = this,
            resultCode = resultCode,
            projectionData = data,
            width = capW,
            height = capH,
            fps = fps,
            onStatus = { _statusFlow.value = it },
            onProjectionLost = { stopEverything(); stopSelf() },
            internetPath = internetPath,
        ).also { it.start() }
    }

    // 미러링 도중에도 접선 서버에 주기적으로 재등록 — MainActivity의 2초 루프는
    // 화면이 떠 있을 때만 돌기 때문에, 주행 중 갱신은 서비스가 책임진다.
    private fun startPeriodicRendezvousRegistration() {
        if (!RendezvousUpdater.isConfigured(this)) return
        scope.launch {
            while (isActive) {
                val cands = localIpCandidates()
                val ip = cands.firstOrNull { it.isHotspot }?.ip ?: cands.firstOrNull()?.ip
                if (ip != null) {
                    RendezvousUpdater.push(this@ScreenCaptureService, ip)
                }
                delay(REGISTER_INTERVAL_MS)
            }
        }
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
        try { webRtcSession?.stop() } catch (_: Throwable) {}
        webRtcSession = null
        // 미러링 종료/앱 스와이프 시 프로브 VPN이 시스템에 남으면 안 됨
        runCatching { GatewayVpnService.stop(this) }
        _statusFlow.value = ""
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
            .setContentText("테슬라 브라우저에서 공용 주소로 접속하세요")
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
