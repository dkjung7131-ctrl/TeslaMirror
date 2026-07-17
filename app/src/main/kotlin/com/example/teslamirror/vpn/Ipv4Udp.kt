package com.example.teslamirror.vpn

/**
 * 최소 IPv4 + UDP 파서/빌더 (tun 릴레이 전용).
 *
 * GatewayVpnService가 addAddress+addRoute로 소유한 가짜 공인 IP로 향하는 차의 UDP는
 * tun fd(L3 원시 IP 패킷)로 들어온다(실차 실측). 이를 파싱해 폰의 실제 libwebrtc 포트로
 * 릴레이하고, 응답을 다시 IPv4/UDP로 감싸(src=가짜IP) tun에 써 넣는다. 옵션 없는 표준 20B
 * IPv4 + 8B UDP만 다룬다.
 */
object Ipv4Udp {

    const val PROTO_UDP = 17
    private const val IPV4_MIN_HEADER = 20
    private const val UDP_HEADER = 8

    fun parse(buf: ByteArray, len: Int): UdpDatagram? {
        if (len < IPV4_MIN_HEADER + UDP_HEADER) return null
        if (((buf[0].toInt() ushr 4) and 0xF) != 4) return null
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (ihl < IPV4_MIN_HEADER || len < ihl + UDP_HEADER) return null
        if ((buf[9].toInt() and 0xFF) != PROTO_UDP) return null

        val srcIp = readIp(buf, 12)
        val dstIp = readIp(buf, 16)
        val srcPort = readU16(buf, ihl)
        val dstPort = readU16(buf, ihl + 2)
        val udpLen = readU16(buf, ihl + 4)
        val payloadLen = udpLen - UDP_HEADER
        if (payloadLen < 0 || ihl + UDP_HEADER + payloadLen > len) return null

        val payload = ByteArray(payloadLen)
        System.arraycopy(buf, ihl + UDP_HEADER, payload, 0, payloadLen)
        return UdpDatagram(srcIp, srcPort, dstIp, dstPort, payload)
    }

    fun build(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = IPV4_MIN_HEADER + UDP_HEADER + payload.size
        val pkt = ByteArray(total)
        pkt[0] = 0x45
        writeU16(pkt, 2, total)
        writeU16(pkt, 6, 0x4000)                       // DF
        pkt[8] = 64
        pkt[9] = PROTO_UDP.toByte()
        writeIp(pkt, 12, srcIp)
        writeIp(pkt, 16, dstIp)
        writeU16(pkt, 10, checksum(pkt, 0, IPV4_MIN_HEADER))

        val udp = IPV4_MIN_HEADER
        writeU16(pkt, udp, srcPort)
        writeU16(pkt, udp + 2, dstPort)
        writeU16(pkt, udp + 4, UDP_HEADER + payload.size)
        System.arraycopy(payload, 0, pkt, udp + UDP_HEADER, payload.size)
        writeU16(pkt, udp + 6, udpChecksum(pkt, srcIp, dstIp, udp, UDP_HEADER + payload.size))
        return pkt
    }

    private fun readIp(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun writeIp(b: ByteArray, o: Int, ip: Int) {
        b[o] = (ip ushr 24).toByte(); b[o + 1] = (ip ushr 16).toByte()
        b[o + 2] = (ip ushr 8).toByte(); b[o + 3] = ip.toByte()
    }

    private fun readU16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun writeU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte()
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L; var i = off; val end = off + len
        while (i + 1 < end) { sum += readU16(b, i).toLong(); i += 2 }
        if (i < end) sum += ((b[i].toInt() and 0xFF) shl 8).toLong()
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun udpChecksum(pkt: ByteArray, srcIp: Int, dstIp: Int, udpOff: Int, udpLen: Int): Int {
        var sum = 0L
        sum += ((srcIp ushr 16) and 0xFFFF).toLong() + (srcIp and 0xFFFF).toLong()
        sum += ((dstIp ushr 16) and 0xFFFF).toLong() + (dstIp and 0xFFFF).toLong()
        sum += PROTO_UDP.toLong() + udpLen.toLong()
        var i = udpOff; val end = udpOff + udpLen
        while (i + 1 < end) { sum += readU16(pkt, i).toLong(); i += 2 }
        if (i < end) sum += ((pkt[i].toInt() and 0xFF) shl 8).toLong()
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val cs = (sum.inv() and 0xFFFF).toInt()
        return if (cs == 0) 0xFFFF else cs
    }

    fun ipToString(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    fun ipFromString(s: String): Int {
        val p = s.split("."); require(p.size == 4)
        return (p[0].toInt() shl 24) or (p[1].toInt() shl 16) or (p[2].toInt() shl 8) or p[3].toInt()
    }
}

data class UdpDatagram(
    val srcIp: Int,
    val srcPort: Int,
    val dstIp: Int,
    val dstPort: Int,
    val payload: ByteArray,
)
