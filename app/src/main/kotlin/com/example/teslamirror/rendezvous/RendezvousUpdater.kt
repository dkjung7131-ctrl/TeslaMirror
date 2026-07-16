package com.example.teslamirror.rendezvous

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloudflare Worker "접선 서버"에 이 폰의 핫스팟 IP를 등록한다.
 *
 * 테슬라는 [WORKER_URL] 하나만 북마크하고, 워커가 접속 순간의 공인 IP를 보고
 * "이 테슬라가 붙어 있는 폰"의 핫스팟 IP로 리다이렉트해 준다.
 * 폰 여러 대가 각자 등록해도 서로 독립적으로 동작한다 (cloudflare/worker.js 참고).
 *
 * 공유 시크릿은 앱 화면에서 입력받아 SharedPreferences에만 보관한다 —
 * APK가 GitHub Releases에 공개로 올라가므로 빌드에 심으면 안 된다.
 */
object RendezvousUpdater {

    /** 전 차량 공통 테슬라 북마크 주소 (cloudflare/worker.js 배포본). */
    const val WORKER_URL = "https://teslamirror.dkjung7131.workers.dev"

    private const val PREFS = "rendezvous"
    private const val KEY_SECRET = "secret"
    private const val FAIL_RETRY_MS = 30_000L   // 실패 시 재시도 간격 (2초 폴링마다 두드리지 않게)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun secret(context: Context): String = prefs(context).getString(KEY_SECRET, "") ?: ""

    fun isConfigured(context: Context): Boolean = secret(context).isNotBlank()

    fun save(context: Context, secret: String) {
        prefs(context).edit().putString(KEY_SECRET, secret.trim()).apply()
        // 설정이 바뀌면 같은 IP라도 다음 틱에 즉시 다시 등록
        lastPushedIp = null
        lastFailedIp = null
    }

    private var lastPushedIp: String? = null
    private var lastFailedIp: String? = null
    private var lastFailedAt = 0L

    /**
     * [ip]가 마지막 성공값과 다를 때만 등록 요청을 보낸다 (UI의 2초 폴링용).
     * 요청을 보냈으면 사용자에게 보여줄 상태 문자열, 보낼 필요가 없었으면 null.
     */
    suspend fun pushIfChanged(context: Context, ip: String): String? {
        if (!isConfigured(context)) return null
        if (ip == lastPushedIp) return null
        val now = SystemClock.elapsedRealtime()
        if (ip == lastFailedIp && now - lastFailedAt < FAIL_RETRY_MS) return null
        return push(context, ip)
    }

    /**
     * 무조건 등록 요청을 보낸다 (미러링 중 주기 재등록용 — 통신사 NAT의 공인 IP가
     * 주행 중 바뀔 수 있어, 워커가 기억하는 공인 IP를 신선하게 유지해야 한다).
     */
    suspend fun push(context: Context, ip: String): String? = withContext(Dispatchers.IO) {
        // 디버그 빌드(.debug)는 접선 서버에 등록하지 않는다 — 개발용 설치가
        // 실사용 릴리스와 별개 deviceId로 목록을 오염시키는 것을 방지.
        if (context.packageName.endsWith(".debug")) return@withContext null
        val secret = secret(context)
        if (secret.isBlank()) return@withContext null
        try {
            val conn = (URL("$WORKER_URL/register").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $secret")
            }
            val body = JSONObject()
                .put("deviceId", deviceId(context))
                .put("name", Build.MODEL)
                .put("hotspotIp", ip)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code == 200) {
                lastPushedIp = ip
                lastFailedIp = null
                "등록 완료 — $ip (${Build.MODEL})"
            } else {
                lastFailedIp = ip
                lastFailedAt = SystemClock.elapsedRealtime()
                if (code == 401) "등록 실패 — 시크릿을 확인하세요"
                else "등록 실패 — 서버 오류 ($code)"
            }
        } catch (_: Throwable) {
            lastFailedIp = ip
            lastFailedAt = SystemClock.elapsedRealtime()
            "등록 실패 — 인터넷(셀룰러) 연결을 확인하세요"
        }
    }

    /** 워커 KV의 키 — 폰마다 고정이면 되고, 재설치로 바뀌어도 무방 (옛 항목은 24시간 뒤 소멸). */
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}
