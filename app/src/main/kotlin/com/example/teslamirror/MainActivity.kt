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
import kotlinx.coroutines.launch
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

private fun currentLocalAddresses(): String {
    val ip = primaryLocalIp() ?: return "핫스팟을 켠 뒤 다시 시작하세요"
    val portSuffix = if (HttpConfig.PORT == 80) "" else ":${HttpConfig.PORT}"
    return "http://$ip$portSuffix"
}

private fun primaryLocalIp(): String? {
    return try {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { ni ->
                // Wi-Fi 계열만 화이트리스트 — 셀룰러(rmnet, seth_*)는 자동 제외
                val name = ni.name.lowercase()
                name.startsWith("wlan") || name.startsWith("ap") ||
                    name.startsWith("softap") || name.startsWith("swlan") ||
                    name.startsWith("p2p") || name.startsWith("tether")
            }
            .flatMap { ni ->
                ni.inetAddresses.toList()
                    .filter { !it.isLinkLocalAddress && it.hostAddress?.contains(':') == false }
                    .map { ni.name to it.hostAddress!! }
            }
        // 일반 Wi-Fi 클라이언트(wlan0) 우선 → PC 테스트 시 자연스러운 IP.
        // 없으면 핫스팟 인터페이스(ap/swlan 등)의 첫번째 → 운전 시 테슬라 접속용.
        candidates.firstOrNull { it.first.lowercase().startsWith("wlan") }?.second
            ?: candidates.firstOrNull()?.second
    } catch (_: Exception) {
        null
    }
}

object HttpConfig {
    const val PORT = 8080
}
