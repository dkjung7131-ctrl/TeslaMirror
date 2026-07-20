package com.example.teslamirror

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import com.example.teslamirror.apps.AppEntry
import com.example.teslamirror.apps.launcherHomeApps

/**
 * 앱 모드 가상 디스플레이용 홈(런처).
 * T맵/카카오맵 등 내비 앱이 설치돼 있으면 맨 위.
 * Back 키로는 종료되지 않음(빈 디스플레이 검정 방지). 홈은 뷰어 파이 제스처로 복귀.
 */
class AppLauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 백 연타로 finish → 가상 디스플레이 검정. 루트(첫 화면)에서는 무시.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.i(TAG, "back on launcher — stay (home root)")
            }
        })
        runCatching {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            )
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = AndroidColor.parseColor("#0B0B0F")
            window.navigationBarColor = AndroidColor.parseColor("#0B0B0F")
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0B0F)) {
                    LauncherHome(onLaunch = { pkg -> launchApp(pkg) })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 뷰어: 런처=홈 → 파이 숨김
        onForegroundChanged?.invoke(true)
        Log.i(TAG, "onResume → launcher foreground")
    }

    override fun onPause() {
        // 다른 앱 실행 중 → 파이(Back/Home/Cancel) 허용
        onForegroundChanged?.invoke(false)
        Log.i(TAG, "onPause → launcher background")
        super.onPause()
    }

    private fun launchApp(packageName: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            Toast.makeText(this, "실행할 수 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch)
            Log.i(TAG, "launched $packageName")
        } catch (t: Throwable) {
            Log.w(TAG, "launch failed $packageName", t)
            Toast.makeText(this, "실행 실패: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "AppLauncher"
        /** AppCastService 가 뷰어에 launcher on/off 통지할 때 연결. */
        @Volatile var onForegroundChanged: ((Boolean) -> Unit)? = null

        fun componentName(packageName: String): String =
            "$packageName/com.example.teslamirror.AppLauncherActivity"
    }
}

@Composable
private fun LauncherHome(onLaunch: (String) -> Unit) {
    val context = LocalContext.current
    val (nav, rest) = remember { launcherHomeApps(context) }
    val bg = Brush.verticalGradient(
        listOf(Color(0xFF12121A), Color(0xFF0B0B0F), Color(0xFF0B0B0F))
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 132.dp),
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(
                        "TeslaMirror",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8E8ED),
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        "홈 · 앱을 고르세요  ·  앱 안에서 가장자리 → Back/Home",
                        fontSize = 14.sp,
                        color = Color(0xFF8B8B98),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            if (nav.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle("내비게이션")
                }
                items(nav, key = { it.packageName }) { app ->
                    AppTile(app, accent = true) { onLaunch(app.packageName) }
                }
            }

            if (rest.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(if (nav.isEmpty()) "앱" else "다른 앱")
                }
                items(rest, key = { it.packageName }) { app ->
                    AppTile(app, accent = false) { onLaunch(app.packageName) }
                }
            }

            if (nav.isEmpty() && rest.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "실행 가능한 앱이 없습니다",
                        color = Color(0xFF666677),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 48.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFF6EA8FE),
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun AppTile(app: AppEntry, accent: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    if (accent) Color(0xFF1A2333) else Color(0xFF1C1C24)
                )
        ) {
            val bmp = remember(app.packageName) {
                app.icon?.toBitmap(144, 144)?.asImageBitmap()
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A36))
                ) {
                    Text(
                        app.label.take(1),
                        fontSize = 26.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            app.label,
            fontSize = 13.sp,
            color = Color(0xFFE0E0E8),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
