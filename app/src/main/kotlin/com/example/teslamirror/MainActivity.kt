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
import com.example.teslamirror.rendezvous.RendezvousUpdater
import com.example.teslamirror.update.UpdateChecker
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
    }
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by ScreenCaptureService.isRunningFlow.collectAsState()
    var fps by remember { mutableStateOf(30) }
    var ipText by remember { mutableStateOf("확인 중…") }

    // 접선 서버 시크릿 — 저장된 값은 폰에만 있음 (SharedPreferences). 서버 주소는 코드에 고정.
    var regConfigured by remember { mutableStateOf(RendezvousUpdater.isConfigured(context)) }
    var regSecretField by remember { mutableStateOf(RendezvousUpdater.secret(context)) }
    var regStatus by remember { mutableStateOf<String?>(null) }

    val currentVersion = remember { UpdateChecker.currentVersion(context) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Int?>(null) }  // null = 다운로드 중 아님

    // 앱 실행 시 1회 조용히 업데이트 확인 (실패는 무시)
    LaunchedEffect(Unit) {
        runCatching { UpdateChecker.checkForUpdate(context) }.getOrNull()?.let { updateInfo = it }
    }

    // 접속 URL을 2초마다 실시간 갱신 — Wi-Fi/핫스팟을 켜고 끄면 화면이 바로 따라감.
    // 핫스팟 IP가 바뀌면 접선 서버(Cloudflare Worker)에도 여기서 자동 등록한다.
    LaunchedEffect(Unit) {
        while (true) {
            val cands = withContext(Dispatchers.IO) { localIpCandidates() }
            ipText = formatViewerUrls(cands, showWorkerUrl = regConfigured)
            val hotspotIp = cands.firstOrNull { it.isHotspot }?.ip
            if (hotspotIp != null) {
                RendezvousUpdater.pushIfChanged(context, hotspotIp)?.let { regStatus = it }
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
            ScreenCaptureService.start(context, result.resultCode, result.data!!, fps)
            ipText = formatViewerUrls(localIpCandidates(), showWorkerUrl = regConfigured)
        }
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

        if (!running) {
            Button(
                onClick = {
                    if (!isHotspotEnabled(context)) {
                        Toast.makeText(
                            context,
                            "핫스팟을 먼저 켜주세요",
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
                        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                            projectionManager.createScreenCaptureIntent(
                                MediaProjectionConfig.createConfigForDefaultDisplay()
                            )
                        } else {
                            projectionManager.createScreenCaptureIntent()
                        }
                        projectionLauncher.launch(captureIntent)
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

private fun formatViewerUrls(cands: List<IpCandidate>, showWorkerUrl: Boolean): String {
    val port = if (HttpConfig.PORT == 80) "" else ":${HttpConfig.PORT}"
    val hotspot = cands.filter { it.isHotspot }
    // 핫스팟이 켜져 있으면 핫스팟 주소만, 없으면 일반 Wi-Fi 주소를 보여준다.
    val show = hotspot.ifEmpty { cands }
    if (show.isEmpty()) return "핫스팟을 켠 뒤 다시 시작하세요"
    val urls = show.map { "http://${it.ip}$port" }.toMutableList()
    // 시크릿이 저장돼 있고 핫스팟이 살아 있으면 접선 서버 주소를 맨 위에 — 테슬라 북마크용
    if (showWorkerUrl && hotspot.isNotEmpty()) {
        urls.add(0, RendezvousUpdater.WORKER_URL)
    }
    return urls.joinToString("\n")
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
