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
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
    var ipText by remember { mutableStateOf(currentLocalAddresses()) }

    val currentVersion = remember { UpdateChecker.currentVersion(context) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Int?>(null) }  // null = 다운로드 중 아님

    // 앱 실행 시 1회 조용히 업데이트 확인 (실패는 무시)
    LaunchedEffect(Unit) {
        runCatching { UpdateChecker.checkForUpdate(context) }.getOrNull()?.let { updateInfo = it }
    }

    // 접속 URL을 2초마다 실시간 갱신 — Wi-Fi/핫스팟을 켜고 끄면 화면이 바로 따라감
    LaunchedEffect(Unit) {
        while (true) {
            ipText = withContext(Dispatchers.IO) { currentLocalAddresses() }
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
            ipText = currentLocalAddresses()
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

private data class IpCandidate(val ip: String, val isHotspot: Boolean)

private fun currentLocalAddresses(): String {
    val cands = localIpCandidates()
    if (cands.isEmpty()) return "핫스팟(또는 Wi-Fi)을 켠 뒤 다시 시작하세요"
    val port = if (HttpConfig.PORT == 80) "" else ":${HttpConfig.PORT}"
    val hotspot = cands.filter { it.isHotspot }
    val others = cands.filter { !it.isHotspot }
    val sb = StringBuilder()
    if (hotspot.isNotEmpty()) {
        sb.append("▶ 테슬라(핫스팟)용:\n")
        hotspot.forEach { sb.append("http://${it.ip}$port\n") }
    }
    if (others.isNotEmpty()) {
        if (hotspot.isNotEmpty()) sb.append("\n")
        sb.append("같은 Wi-Fi 테스트용:\n")
        others.forEach { sb.append("http://${it.ip}$port\n") }
    }
    return sb.toString().trimEnd()
}

/**
 * 접속 가능한 모든 로컬 IP 후보. 핫스팟 인터페이스(테슬라가 붙는 쪽)를 먼저.
 * 폰이 핫스팟 + 일반 Wi-Fi에 동시 연결된 경우(삼성 Wi-Fi 공유) 둘 다 노출해
 * 사용자가 맞는 주소를 고를 수 있게 한다.
 */
private fun localIpCandidates(): List<IpCandidate> {
    return try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                val name = ni.name.lowercase()
                val isHotspot = name.startsWith("ap") || name.startsWith("softap") ||
                    name.startsWith("swlan") || name.startsWith("rndis") ||
                    name.startsWith("tether") || name.startsWith("p2p") || name == "wlan1"
                val isWifiClient = name.startsWith("wlan")
                // Wi-Fi 계열만 — 셀룰러(rmnet, seth_*)는 제외
                if (!isHotspot && !isWifiClient) return@flatMap emptyList()
                ni.inetAddresses.toList()
                    .filter { !it.isLinkLocalAddress && it.hostAddress?.contains(':') == false }
                    .map { IpCandidate(it.hostAddress!!, isHotspot) }
            }
            .distinctBy { it.ip }
            .sortedByDescending { it.isHotspot }   // 핫스팟 IP 먼저
    } catch (_: Exception) {
        emptyList()
    }
}

object HttpConfig {
    const val PORT = 8080
}
