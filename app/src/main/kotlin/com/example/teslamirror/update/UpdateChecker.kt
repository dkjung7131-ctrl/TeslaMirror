package com.example.teslamirror.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 기반 인앱 업데이트.
 *
 * 동작: 최신 릴리스 조회 → 현재 설치 버전과 semver 비교 → 더 높으면 APK 다운로드 → 시스템 설치 화면 호출.
 * 자동 설치가 동작하려면 모든 릴리스 APK가 "동일한 키"로 서명돼야 한다(README의 서명 설정 참고).
 */
object UpdateChecker {

    // 릴리스를 게시하는 저장소. 포크하면 이 값만 바꾸면 됨.
    private const val OWNER = "dkjung7131-ctrl"
    private const val REPO = "TeslaMirror"
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    data class UpdateInfo(
        val latestVersion: String,   // 예: "0.2.0" (태그의 'v' 제거)
        val currentVersion: String,  // 예: "0.1.0"
        val downloadUrl: String,     // .apk 에셋 다운로드 URL
        val notes: String,           // 릴리스 노트(body)
    )

    /** 현재 설치된 앱의 versionName. */
    fun currentVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.0.0"

    /**
     * 최신 릴리스를 확인한다. 새 버전이 있으면 [UpdateInfo], 없으면 null.
     * 네트워크/파싱 실패 시 예외를 던지므로 호출부에서 try/catch 처리.
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val json = httpGet(LATEST_RELEASE_API)
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name").trim()            // 예: "v0.2.0"
        val latest = tag.removePrefix("v").removePrefix("V")
        if (latest.isEmpty()) return@withContext null

        // .apk 에셋 찾기
        val assets = obj.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
        }
        if (apkUrl.isNullOrEmpty()) return@withContext null

        val current = currentVersion(context)
        if (compareVersions(latest, current) <= 0) return@withContext null  // 최신이거나 더 낮음

        UpdateInfo(
            latestVersion = latest,
            currentVersion = current,
            downloadUrl = apkUrl,
            notes = obj.optString("body").trim(),
        )
    }

    /**
     * APK를 cacheDir/updates 에 내려받는다. [onProgress]는 0..100(길이를 모르면 -1).
     * 다운로드된 파일을 반환.
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }              // 이전 다운로드 정리
        val outFile = File(dir, "TeslaMirror-update.apk")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 30000
        }
        conn.inputStream.use { input ->
            val total = conn.contentLength
            outFile.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var downloaded = 0L
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    downloaded += read
                    onProgress(if (total > 0) ((downloaded * 100) / total).toInt() else -1)
                }
            }
        }
        conn.disconnect()
        outFile
    }

    /** 시스템 패키지 설치 화면을 띄운다. (앱에 "출처를 알 수 없는 앱 설치" 허용이 필요) */
    fun installApk(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TeslaMirror")
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 15000
        }
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            .also { conn.disconnect() }
    }

    /** semver 비교: a>b면 양수, 같으면 0, a<b면 음수. "1.2.0" vs "1.2" 같은 길이 차이도 처리. */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
