package com.example.teslamirror.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * 무선 디버깅(`Settings.Global.adb_wifi_enabled`) 자동 제어.
 *
 * WRITE_SECURE_SETTINGS는 일반 설치로는 부여되지 않고 **최초 1회 adb로** 부여해야 한다:
 * ```
 * adb shell pm grant com.example.teslamirror android.permission.WRITE_SECURE_SETTINGS
 * ```
 * 한 번 부여하면 재부팅해도 유지된다 (앱 삭제 시에만 소멸).
 * S25+/One UI 8에서 값 쓰기만으로 무선 디버깅 on/off가 즉시 반영되는 것을 실측 확인.
 * 권한이 없으면 모든 함수가 조용히 no-op — 기존 수동 흐름 그대로 동작한다.
 *
 * 소유권("앱이 켰다")은 SharedPreferences에 기록한다 — 캐스트 중 프로세스가 죽어도
 * 다음 종료 때 되돌릴 수 있고, "사용자가 직접 켜 둔" 상태는 건드리지 않는다.
 */
object AdbWifiToggle {
    private const val TAG = "AdbWifiToggle"
    private const val KEY_ADB_WIFI = "adb_wifi_enabled"
    private const val PREFS = "adbwifi_toggle"
    private const val PREF_ENABLED_BY_APP = "enabled_by_app"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isWifiDebuggingOn(context: Context): Boolean =
        runCatching {
            Settings.Global.getInt(context.contentResolver, KEY_ADB_WIFI, 0) == 1
        }.getOrDefault(false)

    /**
     * 무선 디버깅이 꺼져 있으면 켠다. 켰을 때만 true.
     * adbd 기동에 2~5초 걸리므로 호출측이 재시도 대기를 담당한다.
     */
    @Synchronized
    fun enableIfNeeded(context: Context): Boolean {
        if (!hasPermission(context)) return false
        if (isWifiDebuggingOn(context)) return false
        return runCatching {
            // 소유권 기록이 먼저 — putInt 직후 프로세스가 죽어도 다음 종료 때 되돌린다 (동기 commit)
            prefs(context).edit().putBoolean(PREF_ENABLED_BY_APP, true).commit()
            Settings.Global.putInt(context.contentResolver, KEY_ADB_WIFI, 1)
            Log.i(TAG, "wireless debugging enabled by app")
            true
        }.onFailure { Log.w(TAG, "enable failed", it) }.getOrDefault(false)
    }

    /** 앱이 켠 경우에만 다시 끈다 (사용자가 직접 켜 둔 상태는 건드리지 않음). */
    @Synchronized
    fun disableIfEnabledByApp(context: Context) {
        if (!prefs(context).getBoolean(PREF_ENABLED_BY_APP, false)) return
        prefs(context).edit().putBoolean(PREF_ENABLED_BY_APP, false).commit()
        if (!hasPermission(context)) return
        runCatching {
            Settings.Global.putInt(context.contentResolver, KEY_ADB_WIFI, 0)
            Log.i(TAG, "wireless debugging disabled by app (restore)")
        }.onFailure { Log.w(TAG, "disable failed", it) }
    }
}
