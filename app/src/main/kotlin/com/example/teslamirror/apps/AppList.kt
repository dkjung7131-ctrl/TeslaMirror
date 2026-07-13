package com.example.teslamirror.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** 런처에 뜨는 설치 앱 하나. */
data class AppEntry(val packageName: String, val label: String)

/**
 * 실행 가능한(런처 카테고리) 설치 앱 목록을 이름순으로 반환.
 * 자기 자신은 제외. QUERY_ALL_PACKAGES 권한 필요(매니페스트에 선언됨).
 */
fun installedLaunchableApps(context: Context): List<AppEntry> {
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
            AppEntry(pkg, label)
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}
