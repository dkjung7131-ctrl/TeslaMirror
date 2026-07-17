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
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * 테슬라 브라우저 LAN 격리 우회 게이트웨이 (Tesor 방식) — tun 릴레이.
 *
 * 배경/실측(memory: teslamirror-tesla-browser-lan-isolation):
 * 테슬라 브라우저는 사설 IP로는 UDP를 안 보낸다. 그래서 뷰어에겐 가짜 공인 IP([FAKE_IP])를
 * 후보로 광고한다(WebRtcSession이 offer의 사설 후보를 FAKE_IP로 재작성, 포트 유지).
 *
 * 폰이 차의 게이트웨이(핫스팟)이고 이 서비스가 [FAKE_IP]를 addAddress+addRoute로 tun에
 * 소유하므로, 차가 [FAKE_IP]:P로 보낸 WebRTC UDP는 **tun fd로 들어온다(실차 실측 2026-07-17:
 * "tun ingress" 확인)**. 여기서 그 패킷을 폰의 실제 libwebrtc 포트(핫스팟IP:P — 재작성이 포트를
 * 보존하므로 목적지 포트 P가 그대로 libwebrtc의 사설 후보 포트)로 릴레이하고, libwebrtc의
 * 응답을 src=[FAKE_IP]:P로 다시 IPv4/UDP로 감싸 tun에 써 넣는다. 차 NAT가 브라우저로 되돌린다.
 * 경로는 전부 로컬(핫스팟) → 저지연.
 */
class GatewayVpnService : VpnService() {

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var pumpThread: Thread? = null

    private val relays = ConcurrentHashMap<String, RelaySocket>()
    private val fakeIpInt = Ipv4Udp.ipFromString(FAKE_IP)
    // libwebrtc host 후보가 바인딩된 폰 로컬 주소(swlan0 IPv4). 재작성이 포트를 보존하므로
    // 릴레이는 (apAddr, tun에서 본 dstPort)로 전달하면 libwebrtc 사설 후보 소켓에 도달한다.
    @Volatile private var apAddr: InetAddress = InetAddress.getByName("127.0.0.1")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        startForegroundNotif()
        apAddr = resolveApAddress() ?: InetAddress.getByName("127.0.0.1")
        if (!establish()) { stopSelf(); return START_NOT_STICKY }
        running = true
        pumpThread = Thread({ pump() }, "GatewayVpnPump").also { it.start() }
        startProbeSocket()   // [진단] FAKE_IP:9999에 실소켓 바인딩 — 로컬배달 여부 판정
        Log.i(TAG, "started; $FAKE_IP → relay to ${apAddr.hostAddress}:<dstPort>")
        return START_STICKY
    }

    /**
     * [결정 실험] FAKE_IP:9999에 UDP 소켓을 바인딩하고 수신을 로그.
     * PC(핫스팟 클라이언트=차와 동일 경로)에서 203.0.113.7:9999로 프로브를 쏴서:
     *  - "PROBE RECV" 로그 → addAddress 로컬배달 성립 → VpnService 게이트웨이 방식 유효.
     *  - pump의 "rx" 로그 → 소켓 대신 tun으로 감(포트별 처리 차이).
     *  - 둘 다 없음 → 포워딩 패킷이 앱에 안 옴 → 이 방식 폐기 판정.
     */
    private fun startProbeSocket() {
        // 두 소켓으로 동시 판정: (A) 0.0.0.0:9999 no-protect, (B) FAKE_IP:9998 no-protect.
        for ((tag, bindAddr, port) in listOf(
            Triple("A-wildcard", "0.0.0.0", 9999),
            Triple("B-fakeip", FAKE_IP, 9998),
        )) {
            Thread({
                try {
                    val s = DatagramSocket(null)
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(InetAddress.getByName(bindAddr), port))
                    Log.i(TAG, "PROBE[$tag] bound $bindAddr:$port")
                    val b = ByteArray(2048)
                    while (running) {
                        val p = DatagramPacket(b, b.size)
                        s.receive(p)
                        Log.i(TAG, "PROBE RECV[$tag] ${p.length}B from ${p.address.hostAddress}:${p.port} → local delivery WORKS")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "PROBE[$tag] error: ${t.message}")
                }
            }, "GatewayVpnProbe-$tag").start()
        }
    }

    private fun establish(): Boolean = try {
        tun = Builder()
            .setSession("TeslaMirror")
            .setMtu(MTU)
            // ★ 실험: FAKE_IP를 소유(addAddress)하지 않고 라우트만 건다. 그러면 클라이언트가
            // FAKE_IP로 보낸 패킷은 로컬 INPUT이 아니라 '포워딩' 대상 → 라우트 따라 tun으로.
            .addAddress(TUN_ADDR, 32)    // tun 자체 주소(더미). FAKE_IP는 소유 안 함.
            .addRoute(FAKE_IP, 32)       // → 차의 FAKE_IP 패킷을 포워딩→tun으로
            .setBlocking(true)
            .establish()
        tun != null
    } catch (t: Throwable) {
        Log.e(TAG, "establish failed", t); false
    }

    /** tun 읽기 루프: 차 → FAKE_IP:P UDP를 로컬 libwebrtc(apAddr:P)로 릴레이. */
    private fun pump() {
        val fd = tun ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(MTU)
        var rx = 0L
        while (running) {
            val n = try { input.read(buf) } catch (t: Throwable) { if (running) Log.w(TAG, "tun read", t); break }
            if (n <= 0) continue
            val dg = Ipv4Udp.parse(buf, n) ?: continue
            if (dg.dstIp != fakeIpInt) continue
            rx++
            if (rx <= 3 || rx % 300 == 0L) {
                Log.i(TAG, "rx #$rx ${Ipv4Udp.ipToString(dg.srcIp)}:${dg.srcPort} → $FAKE_IP:${dg.dstPort} (${dg.payload.size}B)")
            }
            val key = "${dg.srcIp}:${dg.srcPort}:${dg.dstPort}"
            val relay = relays.getOrPut(key) {
                RelaySocket(dg.srcIp, dg.srcPort, dg.dstPort, output).also { it.start() }
            }
            relay.sendToLocal(dg.payload)
        }
        Log.i(TAG, "pump ended rx=$rx")
    }

    /**
     * 하나의 (차 소스 + 목적포트)에 대응하는 릴레이. 차→libwebrtc는 sendToLocal,
     * libwebrtc→차는 리더 스레드가 tun에 src=FAKE_IP:dstPort로 재주입.
     */
    private inner class RelaySocket(
        private val carIp: Int,
        private val carPort: Int,
        private val dstPort: Int,          // = libwebrtc 사설 후보 포트(포트 보존 재작성 덕분)
        private val tunOut: FileOutputStream,
    ) {
        private val sock = DatagramSocket().also { protect(it) }   // VPN 우회(로컬 전송)
        private val rbuf = ByteArray(MTU)
        @Volatile private var alive = true
        private val target = InetSocketAddress(apAddr, dstPort)

        fun start() {
            Thread({
                while (alive && running) {
                    val p = DatagramPacket(rbuf, rbuf.size)
                    try { sock.receive(p) } catch (_: Throwable) { break }
                    val payload = ByteArray(p.length)
                    System.arraycopy(rbuf, 0, payload, 0, p.length)
                    val pkt = Ipv4Udp.build(fakeIpInt, dstPort, carIp, carPort, payload)
                    synchronized(tunOut) { runCatching { tunOut.write(pkt) } }
                }
            }, "Relay-$dstPort").start()
        }

        fun sendToLocal(payload: ByteArray) {
            runCatching { sock.send(DatagramPacket(payload, payload.size, target)) }
        }

        fun close() { alive = false; runCatching { sock.close() } }
    }

    override fun onDestroy() {
        running = false
        relays.values.forEach { it.close() }; relays.clear()
        runCatching { tun?.close() }; tun = null
        runCatching { pumpThread?.interrupt() }
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    override fun onRevoke() { stopSelf(); super.onRevoke() }

    private fun resolveApAddress(): InetAddress? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { ni ->
                val n = ni.name.lowercase()
                n.startsWith("ap") || n.startsWith("softap") || n.startsWith("swlan") || n == "wlan1"
            }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLinkLocalAddress && it.hostAddress?.contains(':') == false }
    } catch (_: Throwable) { null }

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

        // 뷰어에 광고할 가짜 공인 IP(TEST-NET-3, RFC5737). 사설 IP가 아니라 테슬라 필터 통과.
        const val FAKE_IP = "203.0.113.7"
        private const val TUN_ADDR = "10.99.99.2"   // tun 더미 주소(FAKE_IP는 소유 안 함)
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
