package com.example.teslamirror.ddns

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * DuckDNS 자동 갱신.
 *
 * 일반 DDNS처럼 공인 IP를 등록하는 게 아니라, 테슬라가 로컬 Wi-Fi에서 접속할
 * "핫스팟 게이트웨이 사설 IP"를 A 레코드로 밀어넣는 용도다.
 * 갱신 요청 자체는 폰의 셀룰러 인터넷으로 나간다.
 *
 * 토큰은 앱 화면에서 입력받아 SharedPreferences에만 보관한다 —
 * APK가 GitHub Releases에 공개로 올라가므로 토큰을 빌드에 심으면 안 된다.
 */
object DuckDnsUpdater {

    /** 개인용 앱이라 도메인은 고정. 테슬라 북마크: http://teslamirror.duckdns.org:8080 */
    const val DOMAIN = "teslamirror"

    private const val PREFS = "duckdns"
    private const val KEY_TOKEN = "token"
    private const val FAIL_RETRY_MS = 30_000L   // 실패 시 재시도 간격 (2초 폴링마다 두드리지 않게)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(context: Context): String = prefs(context).getString(KEY_TOKEN, "") ?: ""

    fun isConfigured(context: Context): Boolean = token(context).isNotBlank()

    fun save(context: Context, token: String) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token.trim())
            .apply()
        // 설정이 바뀌면 같은 IP라도 다음 틱에 즉시 다시 등록
        lastPushedIp = null
        lastFailedIp = null
    }

    private var lastPushedIp: String? = null
    private var lastFailedIp: String? = null
    private var lastFailedAt = 0L

    /**
     * [ip]가 마지막 성공값과 다를 때만 DuckDNS에 갱신 요청을 보낸다.
     * 요청을 보냈으면 사용자에게 보여줄 상태 문자열, 보낼 필요가 없었으면 null.
     */
    suspend fun pushIfChanged(context: Context, ip: String): String? = withContext(Dispatchers.IO) {
        val token = token(context)
        if (token.isBlank()) return@withContext null
        if (ip == lastPushedIp) return@withContext null
        val now = SystemClock.elapsedRealtime()
        if (ip == lastFailedIp && now - lastFailedAt < FAIL_RETRY_MS) return@withContext null

        try {
            val url = URL("https://www.duckdns.org/update?domains=$DOMAIN&token=$token&ip=$ip")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.trim()
            conn.disconnect()
            if (body == "OK") {
                lastPushedIp = ip
                lastFailedIp = null
                "$DOMAIN.duckdns.org → $ip 갱신 완료"
            } else {
                // DuckDNS는 도메인/토큰이 틀리면 본문 "KO"를 준다
                lastFailedIp = ip
                lastFailedAt = now
                "갱신 실패 — 토큰을 확인하세요"
            }
        } catch (_: Throwable) {
            lastFailedIp = ip
            lastFailedAt = now
            "갱신 실패 — 인터넷(셀룰러) 연결을 확인하세요"
        }
    }
}
