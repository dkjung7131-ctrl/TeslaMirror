package com.example.teslamirror.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.teslamirror.MainActivity
import java.io.FileInputStream

/**
 * 테슬라 브라우저 LAN 격리 우회 — "가짜 공인 IP 소유"만 담당 (Tesor/TeslaMirror 방식).
 *
 * 배경(memory: teslamirror-tesla-browser-lan-isolation):
 * 테슬라 브라우저는 사설 IP(10.x/172.16-31.x/192.168.x) 목적지로는 접속/UDP를 아예 막는다.
 * 그래서 뷰어에게는 **공인처럼 보이는 IP**([FAKE_IP])를 후보로 광고해야 한다.
 *
 * 핵심 메커니즘(조사 확인): 비루트 안드로이드에서 **테더링 클라이언트(차)가 포워딩한 패킷은
 * 앱 VpnService의 tun으로 안 들어온다**(PCAPdroid/VPNHotspot 근거 — 그래서 그들은 루트 필요).
 * 대신 이 서비스는 **[FAKE_IP]를 tun의 로컬 주소로 addAddress**해서 폰이 그 IP를 "소유"한다.
 * 그러면 차가 [FAKE_IP]로 보낸 패킷은 *포워딩이 아니라 로컬 배달(INPUT)*되어, 그 주소에
 * 바인딩된 폰의 소켓(libwebrtc host 후보 소켓)이 직접 받는다. 셀룰러로 안 나가고 로컬 종결.
 *
 * 따라서 이 서비스는 라우팅/릴레이/패킷파싱을 하지 않는다. IP 하나만 소유하고 tun을 열어둔다.
 * WebRtcSession은 disableNetworkMonitor로 tun의 [FAKE_IP]까지 열거해 그 위에 host 후보를
 * 만들고(=그 주소에 소켓 바인딩), offer에 [FAKE_IP] 후보를 남겨 광고한다.
 *
 * ⚠️ 미검증(조사상 "Likely"): 위 로컬-배달 경로가 실기기에서 성립하는지 + libwebrtc가 tun
 * 인터페이스에 host 후보를 만드는지는 실차/실기기 확인 필요. 안 되면 대안은 FAKE_IP에
 * 직접 바인딩한 유저스페이스 UDP 릴레이(이 서비스가 소유한 IP:포트 ↔ libwebrtc 실제 포트).
 */
class GatewayVpnService : VpnService() {

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var drainThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        startForegroundNotif()
        if (!establish()) { stopSelf(); return START_NOT_STICKY }
        running = true
        // tun에는 아무것도 안 들어오는 게 정상(로컬 배달됨). 만일을 대비해 읽어 버려 백업 방지.
        drainThread = Thread({ drain() }, "GatewayVpnDrain").also { it.start() }
        Log.i(TAG, "started; owning $FAKE_IP as local address")
        return START_STICKY
    }

    private fun establish(): Boolean = try {
        tun = Builder()
            .setSession("TeslaMirror")
            .setMtu(MTU)
            .addAddress(FAKE_IP, 32)     // ★ 이 IP를 폰 로컬 주소로 소유 → 차 패킷이 로컬 배달됨
            .addRoute(FAKE_IP, 32)       // Builder 최소 라우트 요건 충족(출력용, 무해)
            .setBlocking(true)
            .establish()
        tun != null
    } catch (t: Throwable) {
        Log.e(TAG, "establish failed", t); false
    }

    private fun drain() {
        val fd = tun ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(MTU)
        while (running) {
            val n = try { input.read(buf) } catch (_: Throwable) { break }
            if (n <= 0) continue
            // 여기 도달하면 로컬-배달 가정이 깨진 것(패킷이 tun으로 옴). 진단용 로그.
            Log.w(TAG, "unexpected tun ingress ${n}B — local-delivery assumption may not hold")
        }
    }

    override fun onDestroy() {
        running = false
        runCatching { tun?.close() }; tun = null
        runCatching { drainThread?.interrupt() }
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    override fun onRevoke() { stopSelf(); super.onRevoke() }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "TeslaMirror VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("TeslaMirror 게이트웨이")
            .setContentText("테슬라 로컬 경로 활성화")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    companion object {
        private const val TAG = "GatewayVpn"
        const val ACTION_STOP = "com.example.teslamirror.vpn.STOP"

        // 뷰어에게 광고할 가짜 공인 IP(TEST-NET-3, RFC5737 — 실인터넷에 없는 문서용 대역).
        // 사설 IP가 아니므로 테슬라 브라우저 필터를 통과하고, addAddress로 폰이 소유하므로
        // 차가 이 IP로 보낸 패킷은 로컬 배달된다.
        const val FAKE_IP = "203.0.113.7"
        private const val MTU = 1500
        private const val CHANNEL = "teslamirror_vpn"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GatewayVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GatewayVpnService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
