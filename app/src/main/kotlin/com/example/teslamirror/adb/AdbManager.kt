package com.example.teslamirror.adb

import android.content.Context
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/**
 * 앱 내장 ADB (무선 디버깅) 연결 관리자.
 *
 * 폰이 자기 자신의 adbd(무선 디버깅)에 mDNS로 연결해 셸 권한을 얻는다.
 * Shizuku 없이 scrcpy-server를 구동하기 위한 전제. (libadb-android + SPAKE2 페어링)
 *
 * RSA 키페어와 X509 인증서는 최초 1회 생성해 filesDir에 보관한다 —
 * 재설치하지 않는 한 유지되므로 페어링도 1회로 끝난다.
 */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val mPrivateKey: PrivateKey
    private val mCertificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val keyFile = File(context.filesDir, "adb_key.pk8")
        val certFile = File(context.filesDir, "adb_cert.der")
        if (keyFile.exists() && certFile.exists()) {
            mPrivateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            mCertificate = certFile.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        } else {
            val (priv, cert) = generateKeyAndCert()
            mPrivateKey = priv
            mCertificate = cert
            keyFile.writeBytes(priv.encoded)   // PKCS8 DER
            certFile.writeBytes(cert.encoded)  // X509 DER
        }
    }

    override fun getPrivateKey(): PrivateKey = mPrivateKey
    override fun getCertificate(): Certificate = mCertificate
    override fun getDeviceName(): String = "TeslaMirror"

    private fun generateKeyAndCert(): Pair<PrivateKey, Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
        val kp = kpg.generateKeyPair()
        val publicKey = kp.public
        val privateKey = kp.private

        val subject = "CN=TeslaMirror"
        val algorithmName = "SHA512withRSA"
        val expiry = System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000  // 10년

        val extensions = CertificateExtensions()
        extensions.set(
            "SubjectKeyIdentifier",
            SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier)
        )
        val x500Name = X500Name(subject)
        val notBefore = Date()
        val notAfter = Date(expiry)
        extensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        val validity = CertificateValidity(notBefore, notAfter)

        val info = X509CertInfo()
        info.set("version", CertificateVersion(2))
        info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
        info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithmName)))
        info.set("subject", CertificateSubjectName(x500Name))
        info.set("key", CertificateX509Key(publicKey))
        info.set("validity", validity)
        info.set("issuer", CertificateIssuerName(x500Name))
        info.set("extensions", extensions)

        val cert = X509CertImpl(info)
        cert.sign(privateKey, algorithmName)
        return privateKey to cert
    }

    /**
     * ADB 연결 보장.
     *  1) 무선 디버깅(mDNS 자동 탐색) — 실사용 경로
     *  2) 실패 시 클래식 ADB over TCP(127.0.0.1:5555) — `adb tcpip 5555` 검증/폴백용
     */
    fun ensureConnected(context: Context): Boolean {
        if (isConnected) return true
        runCatching { if (autoConnect(context, 5_000)) return true }
        return runCatching { connect("127.0.0.1", 5555) }.getOrDefault(false)
    }

    /**
     * 바이트 배열을 원격 경로로 푸시한다 (`exec:cat > path`).
     * scrcpy-server.jar를 /data/local/tmp에 밀어넣는 용도.
     */
    fun pushFile(bytes: ByteArray, remotePath: String) {
        val stream: AdbStream = openStream("exec:cat > $remotePath")
        stream.openOutputStream().use { out ->
            out.write(bytes)
            out.flush()
        }
        stream.close()
    }

    /** 짧은 셸 명령 실행 후 표준출력 전체를 반환 (검증/조회용). */
    fun runCommand(command: String): String {
        val stream = openStream("shell:$command")
        return stream.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            .also { runCatching { stream.close() } }
    }

    /**
     * `exec:` 서비스로 명령 실행. scrcpy 서버가 이미 `shell:` 스트림을 점유 중일 때
     * 두 번째 `shell:`은 libadb에서 "Stream closed"/행 → exec:는 별개 서비스라 공존 가능.
     */
    fun runExec(command: String): String {
        val stream = openStream("exec:$command")
        return stream.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            .also { runCatching { stream.close() } }
    }

    companion object {
        @Volatile private var INSTANCE: AdbManager? = null

        fun getInstance(context: Context): AdbManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdbManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
