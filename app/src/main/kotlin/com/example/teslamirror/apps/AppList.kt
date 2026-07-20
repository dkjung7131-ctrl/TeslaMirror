package com.example.teslamirror.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** 런처에 뜨는 설치 앱 하나. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
)

/**
 * 홈(런처) 상단에 우선 배치할 내비게이션 앱 패키지.
 * 설치돼 있으면 맨 앞, 없으면 그냥 빠진 것처럼 보이면 됨.
 */
private val NAV_PRIORITY_PACKAGES = listOf(
    "com.skt.tmap.ku",           // T맵
    "net.daum.android.map",      // 카카오맵
    "com.nhn.android.nmap",      // 네이버지도
    "com.locnall.KimGiSa",       // 아이나비
    "com.google.android.apps.maps", // 구글맵
    "com.waze",                  // Waze
    "com.mnsoft.mappyobn",       // 맵피
    "com.atconnect.map",         // 아틀란
)

/**
 * 실행 가능한(런처 카테고리) 설치 앱 목록을 이름순으로 반환.
 * 자기 자신은 제외. QUERY_ALL_PACKAGES 권한 필요(매니페스트에 선언됨).
 */
fun installedLaunchableApps(context: Context, withIcons: Boolean = false): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val flags = PackageManager.MATCH_ALL
    return pm.queryIntentActivities(intent, flags)
        .asSequence()
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName }
        .map { pkg ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val icon = if (withIcons) {
                runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            } else null
            AppEntry(pkg, label, icon)
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

/**
 * 가상 디스플레이 홈용: 내비 앱(설치분) 우선 → 나머지 이름순.
 * 우선 목록에 없는 내비 패키지는 그냥 빠짐.
 */
fun launcherHomeApps(context: Context): Pair<List<AppEntry>, List<AppEntry>> {
    val all = installedLaunchableApps(context, withIcons = true)
    val byPkg = all.associateBy { it.packageName }
    val nav = NAV_PRIORITY_PACKAGES.mapNotNull { byPkg[it] }
    val navPkgs = nav.map { it.packageName }.toSet()
    val rest = all.filter { it.packageName !in navPkgs }
    return nav to rest
}
