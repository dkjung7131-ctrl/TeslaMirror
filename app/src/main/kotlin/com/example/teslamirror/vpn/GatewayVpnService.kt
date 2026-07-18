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
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 게이트웨이 프로브 서비스.
 *
 * 모드 OWN_CGNAT (상용 TeslaMirror 계열 재현):
 *  - FAKE_IP = 100.99.9.9 (CGNAT/RFC6598 — 테슬라가 막는 10/172.16/192.168 과 다름)
 *  - tun에 addAddress(FAKE_IP) + addRoute(FAKE_IP/32)
 *  - TCP :3333 (상용 MJPEG 포트) + UDP 프로브 소켓
 *  - PC(핫스팟 클라이언트)에서 접속 가능한지가 성공 판정
 *
 * 이전 확정 실패:
 *  - ROUTE_ONLY(203.0.113.7, 미소유) → tun rx=0
 *  - addAddress(203.0.113.7) → 수신 0 (이전 데스크)
 *  - MANAGE_TEST_NETWORKS → signature only, adb grant 불가
 */
class GatewayVpnService : VpnService() {

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var pumpThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private val rxCount = AtomicLong(0)
    private val tcpCount = AtomicLong(0)

    private val relays = ConcurrentHashMap<String, RelaySocket>()
    private val fakeIpInt = Ipv4Udp.ipFromString(FAKE_IP)
    @Volatile private var apAddr: InetAddress = InetAddress.getByName("127.0.0.1")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown("stop-intent")
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) {
            Log.i(TAG, "already running; ignore start")
            return START_STICKY
        }
        startForegroundNotif()
        apAddr = resolveApAddress() ?: InetAddress.getByName("127.0.0.1")
        if (!establish()) {
            teardown("establish-fail")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        pumpThread = Thread({ pump() }, "GatewayVpnPump").also { it.start() }
        startProbes()
        startTcpProbe()
        startHeartbeat()
        Log.i(
            TAG,
            "READY mode=OWN_CGNAT fake=$FAKE_IP ap=${apAddr.hostAddress} " +
                "→ PC: curl http://$FAKE_IP:3333  /  UDP $FAKE_IP:9999"
        )
        // 재시작하지 않음 — 앱/미러링 종료 시 VPN이 남으면 안 됨
        return START_NOT_STICKY
    }

    private fun startProbes() {
        val ap = apAddr.hostAddress ?: "127.0.0.1"
        val specs = listOf(
            Triple("A-wildcard", "0.0.0.0", 9999),
            Triple("B-fakeip", FAKE_IP, 9998),
            Triple("C-ap", ap, 9997),
        )
        for ((tag, bindAddr, port) in specs) {
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
                        Log.i(
                            TAG,
                            "PROBE RECV[$tag] ${p.length}B from ${p.address.hostAddress}:${p.port}"
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "PROBE[$tag] fail ($bindAddr:$port): ${t.message}")
                }
            }, "GatewayVpnProbe-$tag").start()
        }
    }

    /** 상용 앱과 동일 포트 계열 TCP 수신 (HTTP 한 줄 응답). */
    private fun startTcpProbe() {
        Thread({
            try {
                // 0.0.0.0 과 FAKE_IP 둘 다 시도 — 어느 쪽이 바인드/수신되는지 판정
                for ((tag, host) in listOf("T-wildcard" to "0.0.0.0", "T-fakeip" to FAKE_IP)) {
                    Thread({
                        try {
                            val ss = ServerSocket()
                            ss.reuseAddress = true
                            ss.bind(InetSocketAddress(InetAddress.getByName(host), TCP_PORT))
                            Log.i(TAG, "TCP[$tag] listening $host:$TCP_PORT")
                            while (running) {
                                val sock = ss.accept()
                                handleTcp(tag, sock)
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "TCP[$tag] fail: ${t.message}")
                        }
                    }, "GatewayVpnTcp-$tag").start()
                    Thread.sleep(50)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "TCP setup: ${t.message}")
            }
        }, "GatewayVpnTcpBoot").start()
    }

    private fun handleTcp(tag: String, sock: Socket) {
        val n = tcpCount.incrementAndGet()
        val remote = "${sock.inetAddress.hostAddress}:${sock.port}"
        Log.i(TAG, "TCP RECV[$tag] #$n from $remote SUCCESS_TCP")
        try {
            sock.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val first = reader.readLine()
            Log.i(TAG, "TCP[$tag] request: $first")
            val body = "ok n=$n tag=$tag mode=OWN_CGNAT fake=$FAKE_IP\n"
            val writer = OutputStreamWriter(sock.getOutputStream())
            writer.write(
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
            )
            writer.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "TCP[$tag] handle: ${t.message}")
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun startHeartbeat() {
        heartbeatThread = Thread({
            var n = 0
            while (running) {
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                if (!running) break
                n++
                Log.i(
                    TAG,
                    "heartbeat #$n alive tunRx=${rxCount.get()} tcp=${tcpCount.get()} " +
                        "ap=${apAddr.hostAddress} mode=OWN_CGNAT"
                )
            }
        }, "GatewayVpnHb").also { it.start() }
    }

    private fun establish(): Boolean = try {
        // 상용 TeslaMirror 주장: 가상 IP를 폰이 소유 (100.99.9.9)
        tun = Builder()
            .setSession("TeslaMirror-cgnat")
            .setMtu(MTU)
            .addAddress(FAKE_IP, 32)
            .addRoute(FAKE_IP, 32)
            .setBlocking(true)
            .establish()
        val ok = tun != null
        Log.i(TAG, "establish ok=$ok addAddress+addRoute $FAKE_IP/32 (OWN_CGNAT)")
        ok
    } catch (t: Throwable) {
        Log.e(TAG, "establish failed", t); false
    }

    private fun pump() {
        val fd = tun ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(MTU)
        Log.i(TAG, "pump started")
        while (running) {
            val n = try { input.read(buf) } catch (t: Throwable) {
                if (running) Log.w(TAG, "tun read: ${t.message}"); break
            }
            if (n <= 0) continue
            val dg = Ipv4Udp.parse(buf, n)
            if (dg == null) {
                if (rxCount.get() == 0L && tcpCount.get() == 0L) {
                    Log.i(TAG, "tun non-UDP first ${n}B proto=${if (n > 9) buf[9].toInt() and 0xFF else -1}")
                }
                continue
            }
            if (dg.dstIp != fakeIpInt) continue
            val rx = rxCount.incrementAndGet()
            if (rx <= 5 || rx % 100 == 0L) {
                Log.i(
                    TAG,
                    "rx #$rx ${Ipv4Udp.ipToString(dg.srcIp)}:${dg.srcPort} → $FAKE_IP:${dg.dstPort} SUCCESS_TUN_INGRESS"
                )
            }
            val key = "${dg.srcIp}:${dg.srcPort}:${dg.dstPort}"
            val relay = relays.getOrPut(key) {
                RelaySocket(dg.srcIp, dg.srcPort, dg.dstPort, output).also { it.start() }
            }
            relay.sendToLocal(dg.payload)
        }
        Log.i(TAG, "pump ended tunRx=${rxCount.get()}")
    }

    private inner class RelaySocket(
        private val carIp: Int,
        private val carPort: Int,
        private val dstPort: Int,
        private val tunOut: FileOutputStream,
    ) {
        private val sock = DatagramSocket().also { protect(it) }
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

    /** tun 닫기 + 스레드 정지. 시스템 VPN 아이콘이 남는 주원인 = tun fd 미해제. */
    private fun teardown(reason: String) {
        if (!running && tun == null) {
            Log.i(TAG, "teardown($reason) already idle")
            return
        }
        Log.i(TAG, "teardown($reason) tunRx=${rxCount.get()} tcp=${tcpCount.get()}")
        running = false
        relays.values.forEach { it.close() }
        relays.clear()
        runCatching { pumpThread?.interrupt() }
        runCatching { heartbeatThread?.interrupt() }
        pumpThread = null
        heartbeatThread = null
        // tun close = OS가 VPN 연결 해제 (아이콘/라우트 제거)
        runCatching { tun?.close() }
        tun = null
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 최근앱에서 스와이프해도 FGS VPN이 남는 기기 있음 → 강제 해제
        teardown("task-removed")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        teardown("destroy")
        super.onDestroy()
    }

    override fun onRevoke() {
        // 사용자가 시스템 설정에서 VPN 끊을 때
        teardown("revoke")
        stopSelf()
        super.onRevoke()
    }

    private fun resolveApAddress(): InetAddress? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { ni ->
                val n = ni.name.lowercase()
                n.startsWith("ap") || n.startsWith("softap") || n.startsWith("swlan") || n == "wlan1"
            }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLinkLocalAddress && it.hostAddress?.contains(':') == false }
            .also { Log.i(TAG, "resolveApAddress → ${it?.hostAddress ?: "NONE"}") }
    } catch (t: Throwable) {
        Log.w(TAG, "resolveApAddress: ${t.message}"); null
    }

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
            .setContentTitle("TeslaMirror 게이트웨이 (CGNAT probe)")
            .setContentText("OWN $FAKE_IP — TCP :$TCP_PORT")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    companion object {
        private const val TAG = "GatewayVpn"
        const val ACTION_STOP = "com.example.teslamirror.vpn.STOP"
        const val ACTION_PROBE = "com.example.teslamirror.vpn.PROBE"

        /**
         * 상용 hustmobile TeslaMirror Android 가상 IP (FAQ 공개).
         * RFC6598 CGNAT — 테슬라 "사설 LAN" 필터(10/172.16/192.168) 밖일 수 있음.
         */
        const val FAKE_IP = "100.99.9.9"
        private const val TCP_PORT = 3333
        private const val MTU = 1500
        private const val CHANNEL = "teslamirror_vpn"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GatewayVpnService::class.java))
        }

        fun startProbeOnly(context: Context) {
            context.startForegroundService(
                Intent(context, GatewayVpnService::class.java).apply { action = ACTION_PROBE }
            )
        }

        fun stop(context: Context) {
            // startForegroundService로 보내야 이미 FGS인 서비스가 STOP을 받음
            val i = Intent(context, GatewayVpnService::class.java).apply { action = ACTION_STOP }
            try {
                context.startForegroundService(i)
            } catch (_: Throwable) {
                context.startService(i)
            }
        }
    }
}
