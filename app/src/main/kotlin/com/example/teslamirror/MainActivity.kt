package com.example.teslamirror

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.teslamirror.adb.AdbManager
import com.example.teslamirror.adb.AdbWifiToggle
import com.example.teslamirror.input.ImeWatchService
import com.example.teslamirror.rendezvous.RendezvousUpdater
import com.example.teslamirror.update.UpdateChecker
import com.example.teslamirror.vpn.GatewayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen()
                }
            }
        }
        handleDebugIntents(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntents(intent)
    }

    /** adb: am start -n …/.MainActivity -a com.example.teslamirror.SET_SECRET --es secret '…' */
    private fun handleDebugIntents(intent: Intent?) {
        if (intent?.action != ACTION_SET_SECRET) return
        val s = intent.getStringExtra("secret")?.trim().orEmpty()
        if (s.isBlank()) {
            Toast.makeText(this, "secret extra 비어 있음", Toast.LENGTH_SHORT).show()
            return
        }
        RendezvousUpdater.save(this, s)
        Toast.makeText(this, "시크릿 저장됨 (adb)", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_SET_SECRET = "com.example.teslamirror.SET_SECRET"
    }
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by ScreenCaptureService.isRunningFlow.collectAsState()
    val fsStatus by ScreenCaptureService.statusFlow.collectAsState()
    var fps by remember { mutableStateOf(30) }
    var ipText by remember { mutableStateOf("확인 중…") }

    // 모드: false=전체화면 미러링(MediaProjection), true=앱 전용(헤드리스, scrcpy)
    var appMode by remember { mutableStateOf(false) }
    val appRunning by AppCastService.isRunningFlow.collectAsState()
    val appStatus by AppCastService.statusFlow.collectAsState()
    // 버튼 표시 전용. 서비스 Flow 와 섞지 않음(실패 직후 false 로 되돌아가며 중지가 안 보이던 버그 수정).
    // true = 빨간 중지, false = 앱 캐스트 시작. 실패해도 사용자가 중지를 한 번 볼 수 있게 유지 후 실패 문구 표시.
    var appCastUiRunning by remember { mutableStateOf(false) }
    LaunchedEffect(appStatus) {
        if (appStatus.startsWith("시작 실패")) {
            Toast.makeText(context, appStatus, Toast.LENGTH_LONG).show()
            // 실패 시 2초 뒤 시작 버튼으로 (즉시 되돌리면 "안 바뀐다"처럼 보임)
            delay(2000)
            appCastUiRunning = false
        }
    }
    // 서비스가 정상 종료(중지 버튼)되면 UI 도 맞춤
    LaunchedEffect(appRunning) {
        if (!appRunning && appStatus.isEmpty()) appCastUiRunning = false
    }

    // 앱 모드용 ADB: 이미 페어링됐으면 자동 연결. 포트/코드는 최초 1회만.
    val adbPrefs = remember { context.getSharedPreferences("adb_pair", Context.MODE_PRIVATE) }
    var adbConnected by remember { mutableStateOf(false) }
    var adbChecking by remember { mutableStateOf(false) }
    var adbStatusText by remember { mutableStateOf("") }
    var showPairForm by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }

    fun tryAdbAutoConnect(fromUser: Boolean = false) {
        if (adbChecking || pairing) return
        adbChecking = true
        adbStatusText = "무선 디버깅 연결 중…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    AdbManager.getInstance(context).ensureConnected(context)
                }.getOrDefault(false)
            }
            adbChecking = false
            adbConnected = ok
            if (ok) {
                adbPrefs.edit().putBoolean("paired_once", true).apply()
                adbStatusText = "무선 디버깅 연결됨 (코드 재입력 불필요)"
                showPairForm = false
                if (fromUser) {
                    Toast.makeText(context, "ADB 연결됨", Toast.LENGTH_SHORT).show()
                }
            } else {
                val once = adbPrefs.getBoolean("paired_once", false)
                adbStatusText = if (once) {
                    if (AdbWifiToggle.hasPermission(context) && !AdbWifiToggle.isWifiDebuggingOn(context)) {
                        "무선 디버깅 꺼짐 — 캐스트를 시작하면 자동으로 켭니다"
                    } else {
                        "자동 연결 실패 — 설정에서 「무선 디버깅」이 켜져 있는지 확인"
                    }
                } else {
                    "최초 1회 페어링이 필요합니다"
                }
                // 실패 시에만 입력란 노출 (매번 강제 입력 아님)
                showPairForm = true
            }
        }
    }

    // 앱 모드 진입 시 자동 연결 시도 (이미 페어링된 키로 mDNS 접속)
    LaunchedEffect(appMode) {
        if (appMode && !adbConnected && !adbChecking) {
            tryAdbAutoConnect(fromUser = false)
        }
    }

    fun pairAdb() {
        val port = pairPort.trim().toIntOrNull()
        if (port == null || pairCode.trim().length < 6) {
            Toast.makeText(context, "포트와 6자리 코드를 확인하세요", Toast.LENGTH_SHORT).show(); return
        }
        pairing = true
        adbStatusText = "페어링 중…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val mgr = AdbManager.getInstance(context)
                    mgr.pair("127.0.0.1", port, pairCode.trim())
                    mgr.autoConnect(context, 20_000)
                }
            }
            pairing = false
            result.onSuccess { ok ->
                adbConnected = ok
                if (ok) {
                    adbPrefs.edit().putBoolean("paired_once", true).apply()
                    showPairForm = false
                    pairCode = ""
                    adbStatusText = "페어링 완료 — 이제 코드 없이 자동 연결됩니다"
                    Toast.makeText(context, "페어링 + 연결 완료", Toast.LENGTH_LONG).show()
                } else {
                    adbStatusText = "페어링됐지만 연결 실패 — 무선 디버깅 ON 확인"
                    Toast.makeText(
                        context,
                        "페어링됐지만 연결 실패 — 무선 디버깅이 켜져 있는지 확인",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure {
                adbStatusText = "페어링 실패: ${it.message}"
                Toast.makeText(context, "페어링 실패: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 테슬라 동일 경로: STUN 공인 후보만(사설 제거). OFF=사설만(로컬 디버그).
    var internetPath by remember { mutableStateOf(true) }

    // 접선 서버 시크릿 — 저장된 값은 폰에만 있음 (SharedPreferences). 서버 주소는 코드에 고정.
    var regConfigured by remember { mutableStateOf(RendezvousUpdater.isConfigured(context)) }
    var regSecretField by remember { mutableStateOf(RendezvousUpdater.secret(context)) }
    var regStatus by remember { mutableStateOf<String?>(null) }

    val currentVersion = remember { UpdateChecker.currentVersion(context) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Int?>(null) }  // null = 다운로드 중 아님

    // 앱 실행 시 1회 조용히 업데이트 확인 (실패는 무시). 디버그 빌드는 생략.
    LaunchedEffect(Unit) {
        if (!context.packageName.endsWith(".debug")) {
            runCatching { UpdateChecker.checkForUpdate(context) }.getOrNull()?.let { updateInfo = it }
        }
    }

    // 접속 URL을 2초마다 실시간 갱신 — Wi-Fi/핫스팟을 켜고 끄면 화면이 바로 따라감.
    // 로컬 IP가 있으면 접선 서버에 등록(공인 ICE용 공인IP 매칭 + 로컬 디버그용 사설IP 기록).
    LaunchedEffect(Unit) {
        while (true) {
            val cands = withContext(Dispatchers.IO) { localIpCandidates() }
            ipText = formatViewerUrls(context, secretConfigured = regConfigured)
            // 핫스팟 우선, 없으면 집 Wi-Fi(wlan) IP로도 등록 — 인터넷 ICE면 핫스팟 불필요
            val regIp = cands.firstOrNull { it.isHotspot }?.ip ?: cands.firstOrNull()?.ip
            if (regIp != null) {
                RendezvousUpdater.pushIfChanged(context, regIp)?.let { regStatus = it }
            }
            delay(2000)
        }
    }

    fun checkForUpdateManually() {
        if (checking) return
        checking = true
        scope.launch {
            val result = runCatching { UpdateChecker.checkForUpdate(context) }
            checking = false
            result.onSuccess { info ->
                if (info != null) updateInfo = info
                else Toast.makeText(context, "최신 버전입니다 (v$currentVersion)", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "업데이트 확인 실패: 네트워크를 확인하세요", Toast.LENGTH_LONG).show()
            }
        }
    }

    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { if (downloadProgress == null) updateInfo = null },
            title = { Text("업데이트 있음") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("v${info.currentVersion} → v${info.latestVersion}", fontWeight = FontWeight.SemiBold)
                    if (info.notes.isNotEmpty()) {
                        Text(
                            info.notes,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                    when (val p = downloadProgress) {
                        null -> {}
                        -1 -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        else -> {
                            LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("다운로드 중… $p%", fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = downloadProgress == null,
                    onClick = {
                        downloadProgress = -1
                        scope.launch {
                            val dl = runCatching {
                                UpdateChecker.downloadApk(context, info.downloadUrl) { downloadProgress = it }
                            }
                            dl.onSuccess { apk ->
                                downloadProgress = null
                                updateInfo = null
                                UpdateChecker.installApk(context, apk)
                            }.onFailure {
                                downloadProgress = null
                                Toast.makeText(context, "다운로드 실패", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) { Text(if (downloadProgress == null) "업데이트" else "받는 중…") }
            },
            dismissButton = {
                TextButton(
                    enabled = downloadProgress == null,
                    onClick = { updateInfo = null }
                ) { Text("나중에") }
            }
        )
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(
                context, result.resultCode, result.data!!, fps,
                internetPath = internetPath,
            )
            ipText = formatViewerUrls(context, secretConfigured = regConfigured)
        }
    }

    val beginFullscreen = {
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "TeslaMirror",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "v$currentVersion",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "1) 폰 핫스팟 켜기\n" +
            "2) 테슬라 Wi-Fi를 폰 핫스팟에 연결\n" +
            "3) 아래 버튼으로 시작\n" +
            "4) 테슬라 브라우저에서 URL 접속",
            fontSize = 19.sp,
            lineHeight = 28.sp
        )

        // ── 모드 선택 ──
        val anyRunning = running || appRunning
        Card {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("모드", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !appMode, onClick = { appMode = false }, enabled = !anyRunning)
                    Text("전체화면 미러링 (폰 화면 그대로)", fontSize = 18.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = appMode, onClick = { appMode = true }, enabled = !anyRunning)
                    Text("앱 모드 (홈 런처 → 내비 등 선택)", fontSize = 18.sp)
                }
            }
        }

        // ── 앱 모드 UI ──
        if (appMode) {
            Card {
                Column(
                    Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("무선 디버깅", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (adbConnected) adbStatusText
                        else if (adbChecking || pairing) adbStatusText.ifBlank { "연결 중…" }
                        else adbStatusText.ifBlank { "연결 상태 확인 중…" },
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = if (adbConnected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (adbConnected) {
                        Text(
                            if (AdbWifiToggle.hasPermission(context)) {
                                "자동 제어 활성 — 무선 디버깅이 꺼져 있어도 캐스트를 시작하면 앱이 알아서 켭니다 " +
                                    "(앱이 켠 경우 종료 시 되돌림). 설정을 다시 만질 필요가 없습니다."
                            } else {
                                "포트·코드는 설정 화면을 열 때마다 바뀌지만, 한 번 페어링하면 앱이 기억합니다. " +
                                    "평소에는 「무선 디버깅」만 켜 두면 됩니다."
                            },
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 설정 메뉴 점프 — 가능하면 분할 화면으로 (코드 보면서 입력)
                    OutlinedButton(
                        onClick = {
                            // 페어링 입력란 미리 펼침 (분할 후 바로 입력)
                            showPairForm = true
                            if (!openWirelessDebuggingSettings(context)) {
                                Toast.makeText(
                                    context,
                                    "설정 화면을 열 수 없습니다. 개발자 옵션 → 무선 디버깅을 직접 열어 주세요",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "설정이 옆에 열리면 포트·코드를 이 앱에 입력하세요. 안 열리면 최근 앱에서 분할 화면을 켜 보세요.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("무선 디버깅 설정 열기 (분할 시도)", fontSize = 16.sp) }
                    if (!adbConnected && !adbChecking) {
                        Button(
                            onClick = { tryAdbAutoConnect(fromUser = true) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("다시 연결 시도", fontSize = 17.sp) }
                    }
                    // 자동 연결 실패 또는 사용자가 펼친 경우에만 최초 페어링 폼
                    if (showPairForm && !adbConnected) {
                        Text(
                            "최초 1회만 · 「무선 디버깅 설정 열기」→ 무선 디버깅 ON → " +
                                "「페어링 코드로 기기 페어링」의 포트·6자리 코드 입력\n" +
                                "(화면을 다시 열면 숫자가 바뀌는 게 정상 — 그때 보이는 걸 입력)",
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                        OutlinedTextField(
                            value = pairPort, onValueChange = { pairPort = it },
                            label = { Text("페어링 포트") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pairCode, onValueChange = { pairCode = it },
                            label = { Text("페어링 코드 (6자리)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { pairAdb() },
                            enabled = !pairing && !adbChecking,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (pairing) "페어링 중…" else "페어링", fontSize = 18.sp) }
                    } else if (!adbConnected && !showPairForm && !adbChecking) {
                        TextButton(onClick = { showPairForm = true }) {
                            Text("최초 페어링이 필요하면 여기", fontSize = 14.sp)
                        }
                    }
                }
            }

            Text(
                "시작 시 홈(런처)이 뜹니다. T맵·카카오맵 등이 설치돼 있으면 위에 먼저 보이고, 없으면 표시 안 됩니다.",
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 테슬라 화면에 여백 없이 맞추기 (뷰어 비율 학습 → 다음 시작에 반영)
            var fillTesla by remember {
                mutableStateOf(AppCastDisplayPrefs.isFillEnabled(context))
            }
            var displaySummary by remember {
                mutableStateOf(AppCastDisplayPrefs.summaryLabel(context))
            }
            fun refreshDisplaySummary() {
                displaySummary = AppCastDisplayPrefs.summaryLabel(context)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = fillTesla,
                    onCheckedChange = {
                        fillTesla = it
                        AppCastDisplayPrefs.setFillEnabled(context, it)
                        refreshDisplaySummary()
                    },
                    enabled = !appCastUiRunning
                )
                Column(Modifier.weight(1f)) {
                    Text("테슬라 화면 꽉 채우기", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (fillTesla) {
                            "ON · $displaySummary\n접속 후 비율 기억 → 다음 캐스트부터 적용. 여백이 이상하면 초기화"
                        } else {
                            "OFF · 고정 ${AppCastDisplayPrefs.DEFAULT_W}×${AppCastDisplayPrefs.DEFAULT_H}"
                        },
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (fillTesla && AppCastDisplayPrefs.hasViewport(context)) {
                        TextButton(
                            onClick = {
                                AppCastDisplayPrefs.clearViewport(context)
                                refreshDisplaySummary()
                                Toast.makeText(context, "학습 비율 초기화 → 다음엔 1280×800", Toast.LENGTH_SHORT).show()
                            },
                            enabled = !appCastUiRunning
                        ) { Text("학습 비율 초기화", fontSize = 13.sp) }
                    }
                }
            }
            if (!appCastUiRunning) {
                Button(
                    onClick = {
                        when {
                            !RendezvousUpdater.isConfigured(context) ->
                                Toast.makeText(context, "공용 접속 주소 시크릿을 먼저 저장하세요", Toast.LENGTH_LONG).show()
                            adbChecking || pairing ->
                                Toast.makeText(context, "무선 디버깅 연결이 끝날 때까지 잠시만요", Toast.LENGTH_SHORT).show()
                            // 자동 켜기 가능(권한 보유 + 페어링 이력)하면 미연결이어도 시작 허용 —
                            // 캐스트 경로(ScrcpyController)가 무선 디버깅을 켜고 연결한다.
                            !adbConnected && !(AdbWifiToggle.hasPermission(context) &&
                                adbPrefs.getBoolean("paired_once", false)) -> {
                                Toast.makeText(
                                    context,
                                    "무선 디버깅 연결 필요 — 위 「다시 연결」또는 최초 페어링",
                                    Toast.LENGTH_LONG
                                ).show()
                                tryAdbAutoConnect(fromUser = true)
                            }
                            else -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                if (!ImeWatchService.isEnabled(context)) {
                                    Log.i("MainActivity", "ImeWatch not enabled — cast without auto-IME")
                                }
                                appCastUiRunning = true
                                Log.i("MainActivity", "app cast start launcher")
                                try {
                                    AppCastService.start(context, internetPath)
                                } catch (t: Throwable) {
                                    Log.e("MainActivity", "startForegroundService failed", t)
                                    appCastUiRunning = false
                                    Toast.makeText(context, "시작 실패: ${t.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 18.dp),
                    enabled = !adbChecking && !pairing
                ) { Text("앱 캐스트 시작", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
            } else {
                Button(
                    onClick = {
                        appCastUiRunning = false
                        AppCastService.stop(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("중지", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
            }
            if (appStatus.isNotBlank()) {
                Text(
                    appStatus,
                    fontSize = 16.sp,
                    color = if (appStatus.startsWith("시작 실패"))
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            }
        }

        // ── 전체화면 미러링 UI ──
        if (!appMode) {
        Card {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "프레임 속도",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = fps == 30,
                        onClick = { fps = 30 },
                        enabled = !running
                    )
                    Text("30fps (부드러움, 권장)", fontSize = 19.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = fps == 15,
                        onClick = { fps = 15 },
                        enabled = !running
                    )
                    Text("15fps (배터리 절약)", fontSize = 19.sp)
                }
            }
        }
        }

        Card {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "접속 URL",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    ipText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    lineHeight = 26.sp
                )
            }
        }

        // 전체화면·앱 모드 공통: 노트북 실차 대용 테스트는 반드시 ON
        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("테슬라 동일 경로 (공인만)", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "ON=STUN 공인만(노트북 핫스팟=테슬라와 동일). OFF=사설만(로컬 디버그).",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                }
                Switch(
                    checked = internetPath,
                    onCheckedChange = { internetPath = it },
                    enabled = !running && !appRunning
                )
            }
        }

        if (!appMode) {
        if (!running) {
            Button(
                onClick = {
                    if (!regConfigured) {
                        Toast.makeText(
                            context,
                            "공용 접속 주소 시크릿을 먼저 저장하세요 (아래 카드)",
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (!internetPath && !isHotspotEnabled(context)) {
                        // 로컬(사설) ICE만 핫스팟/동일 LAN 필요. 인터넷 ICE(테슬라 경로)는 불필요.
                        Toast.makeText(
                            context,
                            "로컬 경로(인터넷 ICE 끔)는 핫스팟을 먼저 켜주세요",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        beginFullscreen()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 18.dp)
            ) {
                Text(
                    "미러링 시작",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            Button(
                onClick = {
                    ScreenCaptureService.stop(context)
                    GatewayVpnService.stop(context) // 혹시 남은 VPN 정리
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(
                    "미러링 중지",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (running && fsStatus.isNotBlank()) {
            Text(fsStatus, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }

        Card {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "공용 접속 주소 자동 등록",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "시크릿을 저장하면 이 폰의 핫스팟 IP를 접선 서버에 자동 등록합니다. " +
                        "테슬라는 폰이 몇 대든 아래 주소 하나만 북마크하면 됩니다.",
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
                Text(
                    RendezvousUpdater.WORKER_URL,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp
                )
                OutlinedTextField(
                    value = regSecretField,
                    onValueChange = { regSecretField = it },
                    label = { Text("시크릿") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        RendezvousUpdater.save(context, regSecretField)
                        regConfigured = RendezvousUpdater.isConfigured(context)
                        regStatus = null
                        Toast.makeText(
                            context,
                            if (regConfigured) "저장됨 — 핫스팟이 켜지면 자동 등록됩니다"
                            else "저장됨 — 시크릿이 비어 있어 자동 등록은 꺼집니다",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("저장", fontSize = 18.sp) }
                regStatus?.let {
                    Text(it, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Text(
            "팁: 테슬라 차량의 Wi-Fi 설정에서 폰 핫스팟 SSID를 길게 눌러 \"Remain connected in Drive\"를 켜야 주행 중에도 끊기지 않습니다.",
            fontSize = 15.sp,
            lineHeight = 22.sp
        )

        Card {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("버전 v$currentVersion", fontSize = 16.sp)
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { checkForUpdateManually() }) {
                        Text("업데이트 확인", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

internal data class IpCandidate(val ip: String, val isHotspot: Boolean)

private fun formatViewerUrls(context: android.content.Context, secretConfigured: Boolean): String {
    // 평소에는 짧은 URL만 쓰면 됨(워커가 등록된 폰 자동 선택).
    // id= 는 폰이 여러 대이거나 자동 선택이 안 될 때만 필요.
    if (!secretConfigured) return "먼저 아래 '공용 접속 주소'에 시크릿을 저장하세요"
    val id = RendezvousUpdater.deviceId(context)
    return RendezvousUpdater.WORKER_URL +
        "\n\n(자동 선택이 안 될 때만)\n${RendezvousUpdater.WORKER_URL}/?id=$id"
}

/**
 * 무선 디버깅 설정 화면으로 점프.
 * 1) 분할 화면(LAUNCH_ADJACENT) 시도 — 페어링 코드 보면서 이 앱에 입력 가능
 * 2) 실패 시 일반 전체화면
 * 팝업/플로팅 창은 공개 API가 없어 기기마다 달라 보장 불가.
 * 페어링 코드 팝업까지 직접 여는 API도 없음.
 */
private fun openWirelessDebuggingSettings(context: Context): Boolean {
    val attempts = buildList {
        add(Intent("android.settings.ADB_WIRELESS_SETTINGS"))
        add(
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.development.WirelessDebuggingActivity"
            )
        )
        add(
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$WirelessDebuggingActivity"
            )
        )
        add(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }
    for (raw in attempts) {
        // 분할 화면 우선 (N+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val split = Intent(raw).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                )
            }
            if (tryStartSettings(context, split, "split")) return true
        }
        val full = Intent(raw).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStartSettings(context, full, "full")) return true
    }
    return false
}

private fun tryStartSettings(context: Context, intent: Intent, mode: String): Boolean {
    val resolved = intent.resolveActivity(context.packageManager) != null ||
        intent.component != null
    if (!resolved && intent.component == null) {
        // action-only: resolve 실패하면 스킵
        if (intent.action != null && intent.resolveActivity(context.packageManager) == null) {
            return false
        }
    }
    return runCatching {
        context.startActivity(intent)
        Log.i("MainActivity", "opened settings mode=$mode via ${intent.action ?: intent.component}")
        true
    }.getOrDefault(false)
}

/**
 * 접속 가능한 로컬 IPv4 주소. 핫스팟(테슬라가 붙는 쪽)을 먼저.
 * 셀룰러(rmnet 등)는 테슬라가 닿을 수 없으므로 제외한다.
 */
internal fun localIpCandidates(): List<IpCandidate> {
    return try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                val name = ni.name.lowercase()
                val isHotspot = name.startsWith("ap") || name.startsWith("softap") ||
                    name.startsWith("swlan") || name.startsWith("rndis") ||
                    name.startsWith("tether") || name.startsWith("p2p") || name == "wlan1"
                val isWifiClient = name.startsWith("wlan") && !isHotspot
                if (!isHotspot && !isWifiClient) return@flatMap emptyList()
                ni.inetAddresses.toList()
                    .filter { !it.isLinkLocalAddress && it.hostAddress?.contains(':') == false }
                    .map { IpCandidate(it.hostAddress!!, isHotspot) }
            }
            .distinctBy { it.ip }
            .sortedByDescending { it.isHotspot }   // 핫스팟 먼저
    } catch (_: Exception) {
        emptyList()
    }
}

object HttpConfig {
    const val PORT = 8080
}
