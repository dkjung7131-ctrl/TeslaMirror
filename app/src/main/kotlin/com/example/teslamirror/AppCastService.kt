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
import com.example.teslamirror.input.ImeWatchService
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
 * MediaProjection 대신 내장 ADB로 scrcpy-server를 구동해, 가상 디스플레이에
 * 홈(런처)을 띄운 뒤 H.264→JPEG로 WebRTC 전송한다.
 * 사용자가 캐스트 앱을 미리 고를 필요 없음 — 테슬라에서 런처로 내비 등을 선택.
 */
class AppCastService : Service() {

    companion object {
        private const val TAG = "AppCastService"
        // v2: IMPORTANCE_DEFAULT (옛 LOW 채널은 기기에서 안 보이는 경우 많음)
        private const val NOTIF_CHANNEL = "tesla_mirror_app_v2"
        private const val NOTIF_ID = 1002
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

        fun start(context: Context, internetPath: Boolean = true) {
            _isRunningFlow.value = true
            _statusFlow.value = "시작 중…"
            Log.i(TAG, "start() launcher mode internet=$internetPath")
            val i = Intent(context, AppCastService::class.java).apply {
                putExtra(EXTRA_INTERNET_PATH, internetPath)
            }
            // Android 8+: 백그라운드 제한 시 예외 — 호출측 try/catch
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            _isRunningFlow.value = false
            _statusFlow.value = ""
            val i = Intent(context, AppCastService::class.java).apply { action = ACTION_STOP }
            try {
                context.startService(i)
            } catch (_: Throwable) {
                // 백그라운드에서 startService가 막히면 stopService로 직접 종료.
                // startForegroundService(STOP)는 startForeground 없이 stopSelf하는 경로라
                // ForegroundServiceDidNotStartInTimeException 위험이 있다.
                runCatching { context.stopService(Intent(context, AppCastService::class.java)) }
            }
        }
    }

    private var controller: ScrcpyController? = null
    private var webrtc: AppWebRtcSession? = null
    private var decoder: H264ToJpeg? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 시작 세대 카운터 — 늦게 실패한 옛 시작 스레드가 새 캐스트를 정리하는 것 방지. */
    @Volatile private var castGeneration = 0

    private var internetPathActive = true
    /** 런처가 홈(첫 화면)인지 — 뷰어 파이 표시 여부. */
    @Volatile private var lastOnLauncher = true
    private var launcherStatePulse: Runnable? = null
    private var activeDisplayW = DISPLAY_WIDTH
    private var activeDisplayH = DISPLAY_HEIGHT

    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 인터넷 ICE면 핫스팟 불필요 — 꺼져 있어도(또는 원래 꺼져 있어도) 유지
            if (internetPathActive) return
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
        val internetPath = intent?.getBooleanExtra(EXTRA_INTERNET_PATH, true) ?: true
        internetPathActive = internetPath
        Log.i(TAG, "onStartCommand launcher internet=$internetPath")

        // 전체화면과 동일하게 시그널링(워커)에 시크릿 필요.
        if (!RendezvousUpdater.isConfigured(this)) {
            publishUi(running = false, status = "시작 실패: 공용 접속 주소 시크릿을 먼저 저장하세요")
            stopSelf(); return START_NOT_STICKY
        }

        // 이미 기동 중이면 중복 start 무시 (버튼 연타)
        if (controller != null || webrtc != null) {
            Log.i(TAG, "already starting/running — ignore duplicate start")
            return START_STICKY
        }
        // 시작 세대 — 오래 블록된 시작 스레드(최대 ~20초)가 뒤늦게 실패했을 때
        // "그 사이 새로 시작된 캐스트"를 잘못 정리하지 않도록 구분한다.
        castGeneration += 1
        val gen = castGeneration

        val modeLabel = "런처"
        // 테슬라 뷰포트 비율 학습값 → 가상 디스플레이 해상도 (fill 옵션)
        val (dispW, dispH) = AppCastDisplayPrefs.resolveDisplaySize(this)
        activeDisplayW = dispW
        activeDisplayH = dispH
        startInForeground(modeLabel)
        // scrcpy 준비 전에 먼저 빨간 중지 버튼으로 (사용자는 "시작→중지" 전환을 기대함)
        val sizeHint = AppCastDisplayPrefs.summaryLabel(this)
        publishUi(running = true, status = "시작 중… $sizeHint")

        // 전송: 전체화면과 같은 워커+STUN+JPEG 데이터채널.
        lateinit var dec: H264ToJpeg
        val rtc = AppWebRtcSession(
            context = this,
            onStatus = { msg -> publishUi(running = true, status = msg) },
            onViewerMessage = { json -> handleInput(json) },
            onConnected = {
                // resetVideo()는 인코더 재시작 중 패킷 경계를 깨뜨려 bad packet size 로
                // 영상 스레드가 죽던 원인이었음(실측: CONNECTED 직후 100ms). IDR 강제 대신
                // 캐시 JPEG 즉시 전송 + 다음 키프레임 자연 대기.
                Log.i(TAG, "viewer connected — flush JPEG (no resetVideo)")
                dec.flushLastJpeg()
                // 뷰어 늦게 붙으면 파이 on/off 상태 재전송 (콜백 시점엔 webrtc 이미 할당됨)
                webrtc?.pushControlJson("""{"t":"launcher","on":$lastOnLauncher}""")
            },
            internetPath = internetPath,
        ).also { webrtc = it }

        dec = H264ToJpeg(
            width = dispW,
            height = dispH,
            shouldEncode = { rtc.canSend() },
            onJpeg = { jpeg -> rtc.pushJpeg(jpeg) },
        ).also { decoder = it }

        val launcherComp = AppLauncherActivity.componentName(packageName)
        val ctrl = ScrcpyController(
            context = this,
            displayWidth = dispW,
            displayHeight = dispH,
            dpi = DISPLAY_DPI,
            maxFps = MAX_FPS,
            targetComponent = launcherComp,
            onConfig = { cfg -> dec.submitConfig(cfg) },
            onFrame = { data, key -> dec.submitFrame(data, key) },
            onError = { msg -> Log.w(TAG, msg); publishUi(running = true, status = msg) },
        ).also { controller = it }

        Thread {
            try {
                Log.i(TAG, "starting webrtc+scrcpy launcher=$launcherComp ${dispW}x$dispH internet=$internetPath")
                rtc.start()
                // 런처=홈(첫 화면)일 때 뷰어 파이 숨김 / 앱 진입 시 표시
                AppLauncherActivity.onForegroundChanged = { onLauncher ->
                    lastOnLauncher = onLauncher
                    // 메인 스레드에서 DC 전송 (상태 전환 직후 누락 방지)
                    mainHandler.post {
                        rtc.pushControlJson("""{"t":"launcher","on":$onLauncher}""")
                    }
                    Log.i(TAG, "viewer launcher=$onLauncher")
                }
                lastOnLauncher = true
                // 직전 종료 때 앱이 무선 디버깅을 껐을 수 있음 — 여기서 켜고 연결부터 확보.
                // (forceDarkModeForCast가 ADB를 쓰므로 연결이 먼저여야 다크모드가 실제로 적용됨)
                runCatching {
                    com.example.teslamirror.adb.AdbManager.getInstance(this)
                        .ensureConnected(this, autoEnable = true)
                }
                // 런처/맵이 라이트 테마로 뜨면 흰 배경+흰 글자 → 가독성 붕괴.
                // 캐스트 중에만 야간 모드 강제 (중지에 이전 값 복구).
                forceDarkModeForCast()
                ctrl.start()
                // 시작 직후 홈 상태 (런처가 곧 뜸)
                rtc.pushControlJson("""{"t":"launcher","on":true}""")
                // DC 폭주 때 1회 유실 대비 — 상태 주기 재전송
                launcherStatePulse?.let { mainHandler.removeCallbacks(it) }
                val pulse = object : Runnable {
                    override fun run() {
                        if (webrtc == null) return
                        webrtc?.pushControlJson("""{"t":"launcher","on":$lastOnLauncher}""")
                        mainHandler.postDelayed(this, 2500)
                    }
                }
                launcherStatePulse = pulse
                mainHandler.postDelayed(pulse, 1500)
                bindImeWatch(rtc)
                // 가상 디스플레이에 소프트키보드 표시 (Gboard 권장 — Tesor와 동일 계열 UX)
                ensureSoftKeyboardReady()
                val gboard = isGboardInstalled()
                val imeHint = if (gboard) {
                    "검색창 탭 → 화면에 키보드 (Gboard)"
                } else {
                    "Gboard 설치 권장 · 검색창 탭 시 키보드"
                }
                val sizeLabel = "${dispW}x$dispH"
                publishUi(running = true, status = "실행 중 — 홈 · $sizeLabel · $imeHint")
                updateNotification(modeLabel, "$sizeLabel · 홈에서 앱 선택")
                // 로컬 ICE일 때만 핫스팟 꺼짐 감시
                if (!internetPath) registerReceiverSafely()
                Log.i(TAG, "started OK ${dispW}x$dispH gboard=$gboard imeWatch=${ImeWatchService.isEnabled(this@AppCastService)}")
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                if (gen != castGeneration) {
                    // 그 사이 사용자가 중지→재시작함 — 새 캐스트를 건드리면 안 됨
                    Log.i(TAG, "stale start thread (gen=$gen) — skip teardown")
                    return@Thread
                }
                // 실패 시 빨간 중지 → 다시 시작 버튼 + 실패 문구
                stopEverything(clearStatus = false)
                publishUi(running = false, status = "시작 실패: ${t.message}")
                stopSelf()
            }
        }.apply { isDaemon = true; name = "AppCastStart" }.start()

        return START_STICKY
    }

    /** UI StateFlow 는 항상 메인 스레드에서 갱신 (Compose 반영 보장). */
    private fun publishUi(running: Boolean, status: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _isRunningFlow.value = running
            _statusFlow.value = status
        } else {
            mainHandler.post {
                _isRunningFlow.value = running
                _statusFlow.value = status
            }
        }
    }

    private fun handleInput(json: String) {
        try {
            val o = JSONObject(json)
            when (o.getString("t")) {
                // 테슬라/뷰어 창 크기 — 다음 캐스트 해상도 학습 (컨트롤러 없어도 저장)
                "viewport" -> {
                    val w = o.optInt("w", 0)
                    val h = o.optInt("h", 0)
                    AppCastDisplayPrefs.saveViewport(this, w, h)
                    Log.i(TAG, "viewport from viewer ${w}x$h (next cast if fill on)")
                }
                else -> handleControlWithController(o)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "handleInput failed: $json", t)
        }
    }

    private fun handleControlWithController(o: JSONObject) {
        val c = controller ?: return
        when (o.getString("t")) {
            "touch" -> {
                // a: 0=DOWN 1=UP 2=MOVE 5=POINTER_DOWN 6=POINTER_UP (Android MotionEvent)
                val a = o.getInt("a")
                val x = o.getInt("x")
                val y = o.getInt("y")
                val id = o.optLong("id", 0L)
                if (a != ScrcpyProtocol.ACTION_MOVE) {
                    Log.i(TAG, "touch a=$a id=$id x=$x y=$y ${c.videoWidth}x${c.videoHeight}")
                }
                c.sendControl(
                    ScrcpyProtocol.injectTouch(a, id, x, y, c.videoWidth, c.videoHeight)
                )
            }
            "scroll" -> {
                val x = o.getInt("x")
                val y = o.getInt("y")
                val h = o.optDouble("h", 0.0).toFloat()
                val v = o.optDouble("v", 0.0).toFloat()
                Log.i(TAG, "scroll x=$x y=$y h=$h v=$v")
                c.sendControl(
                    ScrcpyProtocol.injectScroll(x, y, c.videoWidth, c.videoHeight, h, v)
                )
            }
            "key" -> {
                val a = if (o.getInt("a") == 0) ScrcpyProtocol.KEY_DOWN else ScrcpyProtocol.KEY_UP
                c.sendControl(ScrcpyProtocol.injectKeycode(a, o.getInt("code"), 0, 0))
            }
            "text" -> {
                val s = o.getString("s")
                val withEnter = o.optBoolean("enter", true)
                Log.i(TAG, "text inject len=${s.length} enter=$withEnter s=$s")
                // 한글: injectText 가 가상 디스플레이에서 무시되는 경우 많음 → clipboard paste
                c.sendControl(ScrcpyProtocol.setClipboard(s, paste = true))
                // 보조: injectText 도 시도 (ASCII/일부 IME)
                c.sendControl(ScrcpyProtocol.injectText(s))
                if (withEnter) {
                    mainHandler.postDelayed({
                        runCatching {
                            val ctrl = controller ?: return@runCatching
                            ctrl.sendControl(
                                ScrcpyProtocol.injectKeycode(
                                    ScrcpyProtocol.KEY_DOWN, ScrcpyProtocol.KEYCODE_ENTER, 0, 0
                                )
                            )
                            ctrl.sendControl(
                                ScrcpyProtocol.injectKeycode(
                                    ScrcpyProtocol.KEY_UP, ScrcpyProtocol.KEYCODE_ENTER, 0, 0
                                )
                            )
                            Log.i(TAG, "text enter done")
                        }
                    }, 180)
                }
            }
            "back" -> {
                c.sendControl(ScrcpyProtocol.backOrScreenOn(ScrcpyProtocol.KEY_DOWN))
                c.sendControl(ScrcpyProtocol.backOrScreenOn(ScrcpyProtocol.KEY_UP))
            }
            "home" -> {
                // KEYCODE_HOME 은 가상 디스플레이(장식 없음)에서 무시되는 경우가 많음
                // → 우리 런처를 해당 displayId 에 직접 띄움 (ADB shell 동시 불필요)
                mainHandler.post { relaunchAppLauncher() }
            }
        }
    }

    /**
     * 가상 디스플레이 홈으로 복귀. KEYCODE_HOME 대신 런처를 displayId 에 직접 start.
     * (vd_system_decorations=false 에선 HOME 키가 안 먹는 경우가 많음)
     */
    private fun relaunchAppLauncher() {
        val id = controller?.displayId ?: -1
        if (id < 0) {
            Log.w(TAG, "relaunch launcher: no displayId yet")
            // 폴백: 키 주입
            val c = controller ?: return
            c.sendControl(
                ScrcpyProtocol.injectKeycode(ScrcpyProtocol.KEY_DOWN, ScrcpyProtocol.KEYCODE_HOME, 0, 0)
            )
            c.sendControl(
                ScrcpyProtocol.injectKeycode(ScrcpyProtocol.KEY_UP, ScrcpyProtocol.KEYCODE_HOME, 0, 0)
            )
            return
        }
        try {
            val intent = Intent(this, AppLauncherActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            if (Build.VERSION.SDK_INT >= 26) {
                val opts = ActivityOptions.makeBasic().apply { launchDisplayId = id }
                startActivity(intent, opts.toBundle())
            } else {
                startActivity(intent)
            }
            Log.i(TAG, "relaunch AppLauncher on display=$id")
        } catch (t: Throwable) {
            Log.w(TAG, "relaunch AppLauncher failed display=$id", t)
        }
    }

    private fun isGboardInstalled(): Boolean =
        runCatching {
            packageManager.getPackageInfo("com.google.android.inputmethod.latin", 0)
            true
        }.getOrDefault(false)

    /** 캐스트 중 야간 모드 강제 전 값 (중지 시 복구). null = 아직 안 건드림. */
    private var savedUiNightMode: String? = null

    /**
     * 가상 디스플레이에서 내비 앱이 라이트 테마로 뜨면 흰 배경+흰 라벨로 안 보이는 경우가 있음.
     * 시스템 야간 모드를 켠 뒤 앱을 띄우면 다크 UI를 쓰는 앱이 많음 (폰 본화면 설정도 잠시 따라감).
     */
    private fun forceDarkModeForCast() {
        val adb = runCatching { com.example.teslamirror.adb.AdbManager.getInstance(this) }.getOrNull() ?: return
        runCatching {
            val prev = adb.runCommand("settings get secure ui_night_mode").trim()
            savedUiNightMode = prev.ifBlank { "null" }
            // 2 = yes(night). 0=auto 1=no 2=yes
            adb.runCommand("settings put secure ui_night_mode 2")
            adb.runCommand("cmd uimode night yes")
            Log.i(TAG, "force night mode (was ui_night_mode=$prev)")
        }.onFailure { Log.w(TAG, "forceDarkMode failed", it) }
    }

    private fun restoreDarkModeAfterCast() {
        val prev = savedUiNightMode ?: return
        savedUiNightMode = null
        val adb = runCatching { com.example.teslamirror.adb.AdbManager.getInstance(this) }.getOrNull() ?: return
        runCatching {
            val restore = when {
                prev.isBlank() || prev.equals("null", ignoreCase = true) -> "0"
                else -> prev.filter { it.isDigit() }.ifBlank { "0" }
            }
            adb.runCommand("settings put secure ui_night_mode $restore")
            when (restore) {
                "2" -> adb.runCommand("cmd uimode night yes")
                "1" -> adb.runCommand("cmd uimode night no")
                else -> adb.runCommand("cmd uimode night auto")
            }
            Log.i(TAG, "restored ui_night_mode=$restore")
        }.onFailure { Log.w(TAG, "restoreDarkMode failed", it) }
    }

    /** 가상 디스플레이 IME가 잘 뜨도록 기본 입력기/설정 보정 (실패해도 캐스트는 계속). */
    private fun ensureSoftKeyboardReady() {
        val adb = runCatching { com.example.teslamirror.adb.AdbManager.getInstance(this) }.getOrNull() ?: return
        // 하드웨어 키보드 있어도 소프트 키보드 표시
        runCatching {
            adb.runCommand("settings put secure show_ime_with_hard_keyboard 1")
        }
        // Gboard 있으면 활성화 시도 (이미 기본이어도 무해)
        if (isGboardInstalled()) {
            runCatching {
                adb.runCommand(
                    "ime enable com.google.android.inputmethod.latin/.LatinIME; " +
                        "ime set com.google.android.inputmethod.latin/.LatinIME"
                )
            }
            Log.i(TAG, "Gboard enable/set attempted")
        } else {
            Log.w(TAG, "Gboard not installed — install recommended for Korean IME on virtual display")
        }
    }

    private fun bindImeWatch(rtc: AppWebRtcSession) {
        ImeWatchService.listener = { show ->
            // 보조: 뷰어 하단 입력바 (소프트키보드가 안 뜰 때 fallback)
            val json = if (show) """{"t":"ime","show":true}""" else """{"t":"ime","show":false}"""
            rtc.pushControlJson(json)
            Log.i(TAG, "ime watch → viewer show=$show")
        }
    }

    private fun unbindImeWatch() {
        ImeWatchService.listener = null
    }

    private fun stopEverything(clearStatus: Boolean = true) {
        AppLauncherActivity.onForegroundChanged = null
        launcherStatePulse?.let { mainHandler.removeCallbacks(it) }
        launcherStatePulse = null
        unbindImeWatch()
        unregisterReceiverSafely()
        runCatching { controller?.stop() }
        runCatching { decoder?.stop() }
        runCatching { webrtc?.stop() }
        controller = null; decoder = null; webrtc = null
        // restoreDarkModeAfterCast는 ADB 소켓 I/O — 메인 스레드(중지 버튼/onDestroy 경로)에서
        // 직접 부르면 NetworkOnMainThreadException으로 조용히 실패한다. 꼬리 작업을 스레드로:
        // ① 다크모드 복원(살아있는 adbd 필요) → ② 앱이 켠 무선 디버깅 되돌리기 — 순서 고정.
        Thread {
            runCatching { restoreDarkModeAfterCast() }
            runCatching { com.example.teslamirror.adb.AdbWifiToggle.disableIfEnabledByApp(this) }
        }.apply { isDaemon = true; name = "AppCastStopTail" }.start()
        if (clearStatus) {
            publishUi(running = false, status = "")
        } else {
            // running 만 false — status 는 호출측이 실패 문구로 덮어씀
            if (Looper.myLooper() == Looper.getMainLooper()) {
                _isRunningFlow.value = false
            } else {
                mainHandler.post { _isRunningFlow.value = false }
            }
        }
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

    private fun startInForeground(modeLabel: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotif(modeLabel, "시작하는 중…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotif(modeLabel, "시작하는 중…"))
        }
    }

    private fun updateNotification(modeLabel: String, detail: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(modeLabel, detail))
    }

    private fun buildNotif(modeLabel: String, detail: String): Notification {
        val stopIntent = Intent(this, AppCastService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("$modeLabel 앱 모드 실행 중")
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_notification_mirror)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPi)
            .addAction(0, "중지", stopPi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // LOW 면 일부 기기에서 알림 서랍에 거의 안 보임 → DEFAULT
        val ch = NotificationChannel(
            NOTIF_CHANNEL,
            "TeslaMirror 앱 캐스트",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "앱 모드 미러링이 켜져 있을 때 표시"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * 홈/최근앱에서 앱을 치워도 캐스트는 유지.
     * (예전: stopEverything → 알림·미러링 동시 종료)
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed — keep AppCastService running")
        runCatching {
            updateNotification("런처", "백그라운드 실행 중 — 알림에서 중지")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() { stopEverything(); super.onDestroy() }
}
