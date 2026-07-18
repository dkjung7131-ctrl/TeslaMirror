package com.example.teslamirror.capture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * scrcpy가 주는 H.264(Annex-B) 스트림을 디코드해 JPEG로 변환한다(앱 모드 전송용).
 * 앱 모드 뷰어(테슬라 브라우저)는 전체화면과 동일한 **JPEG 데이터채널** 뷰어라 H.264를 그대로
 * 못 그리므로 폰에서 디코드→JPEG 재인코딩한다.
 *
 * ⚠️ **ByteBuffer(Surface 없음) 디코드**를 쓴다. Surface(ImageReader) 출력은 이 기기(S936N)에서
 * GPU 전용 버퍼라 planes를 CPU로 못 읽고 SIGSEGV/JNI-abort 실측. 코덱 소유 출력 버퍼는 CPU
 * 접근 가능하므로 `getOutputImage`로 YUV_420_888을 받아 NV21→YuvImage.compressToJpeg 한다.
 *
 * config(SPS/PPS)는 [submitConfig], 프레임은 [submitFrame]. 별도 config 없으면 키프레임 인라인
 * SPS/PPS로 시작. [shouldEncode]가 false면 lastJpeg 캐시만 하고 전송은 건너뛴다.
 */
class H264ToJpeg(
    private val width: Int,
    private val height: Int,
    private val quality: Int = 60,
    private val shouldEncode: () -> Boolean = { true },
    private val onJpeg: (ByteArray) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var started = false
    @Volatile private var configData: ByteArray? = null
    private val baos = ByteArrayOutputStream(128 * 1024)

    fun submitConfig(config: ByteArray) = ensureStarted(config)

    @Synchronized
    private fun ensureStarted(csd: ByteArray?) {
        if (csd != null) configData = csd
        if (started) return
        try {
            val t = HandlerThread("H264Decode").also { it.start() }
            thread = t
            handler = Handler(t.looper)

            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatYUV420Flexible)
                configData?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    synchronized(this@H264ToJpeg) { inputQueue.add(index) }
                    drainInput(mc)
                }
                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        val img = mc.getOutputImage(index)   // ByteBuffer 모드 → CPU 접근 가능
                        if (img != null) {
                            imgCount++
                            if (imgCount <= 3 || imgCount % 120 == 0L) Log.i(TAG, "img#$imgCount ${img.width}x${img.height}")
                            runCatching { encodeAndCache(img) }.onFailure { Log.w(TAG, "encode", it) }
                            img.close()
                        }
                    } finally {
                        runCatching { mc.releaseOutputBuffer(index, false) }
                    }
                }
                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "codec error: ${e.message}")
                }
                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {}
            }, handler)
            c.configure(fmt, null, null, 0)   // Surface 없음 = ByteBuffer 모드
            c.start()
            codec = c
            started = true
            startReemitLoop()
            Log.i(TAG, "decoder started ${width}x$height (ByteBuffer)")
        } catch (t: Throwable) {
            Log.e(TAG, "decoder init failed", t)
        }
    }

    fun submitFrame(frame: ByteArray, isKey: Boolean) {
        if (!started) ensureStarted(null)
        if (!started) return
        synchronized(this) { pendingFrames.add(frame to isKey) }
        codec?.let { drainInput(it) }
    }

    private val inputQueue = ArrayDeque<Int>()
    private val pendingFrames = ArrayDeque<Pair<ByteArray, Boolean>>()

    @Synchronized
    private fun drainInput(mc: MediaCodec) {
        while (inputQueue.isNotEmpty() && pendingFrames.isNotEmpty()) {
            val index = inputQueue.removeFirst()
            val (frame, _) = pendingFrames.removeFirst()
            try {
                val buf = mc.getInputBuffer(index) ?: continue
                buf.clear(); buf.put(frame)
                mc.queueInputBuffer(index, 0, frame.size, System.nanoTime() / 1000, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "queueInput failed", t)
            }
        }
    }

    private var imgCount = 0L
    private var jpegCount = 0L
    @Volatile private var lastJpeg: ByteArray? = null
    @Volatile private var lastEmitMs = 0L
    private var reemitThread: Thread? = null
    private var nv21: ByteArray? = null

    /** YUV_420_888(CPU 버퍼) → NV21 → JPEG. lastJpeg 캐시 + canSend 시 전송. */
    private fun encodeAndCache(img: Image) {
        val w = img.width; val h = img.height
        val out = nv21?.takeIf { it.size == w * h * 3 / 2 } ?: ByteArray(w * h * 3 / 2).also { nv21 = it }
        yuv420ToNv21(img, out)
        baos.reset()
        YuvImage(out, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, baos)
        val bytes = baos.toByteArray()
        jpegCount++
        if (jpegCount <= 3 || jpegCount % 120 == 0L) Log.i(TAG, "jpeg#$jpegCount ${bytes.size}B")
        lastJpeg = bytes
        if (shouldEncode()) {
            lastEmitMs = System.currentTimeMillis()
            onJpeg(bytes)
        }
    }

    /** YUV_420_888(스트라이드/픽셀스트라이드 고려) → NV21(Y + 인터리브 VU). */
    private fun yuv420ToNv21(image: Image, out: ByteArray) {
        val w = image.width; val h = image.height
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        var pos = 0
        val yBuf = yP.buffer; val yRow = yP.rowStride
        val yRowTmp = ByteArray(yRow)
        for (row in 0 until h) {
            yBuf.position(row * yRow)
            val n = minOf(yRow, yBuf.remaining())
            yBuf.get(yRowTmp, 0, n)
            System.arraycopy(yRowTmp, 0, out, pos, w)
            pos += w
        }
        val uBuf = uP.buffer; val vBuf = vP.buffer
        val uRow = uP.rowStride; val vRow = vP.rowStride
        val uPix = uP.pixelStride; val vPix = vP.pixelStride
        val cw = w / 2; val ch = h / 2
        for (row in 0 until ch) {
            var uIdx = row * uRow
            var vIdx = row * vRow
            for (col in 0 until cw) {
                out[pos++] = vBuf.get(vIdx)
                out[pos++] = uBuf.get(uIdx)
                uIdx += uPix; vIdx += vPix
            }
        }
    }

    private fun startReemitLoop() {
        reemitThread = Thread {
            while (started) {
                try { Thread.sleep(REEMIT_INTERVAL_MS) } catch (_: Throwable) { break }
                val j = lastJpeg ?: continue
                if (!shouldEncode()) continue
                if (System.currentTimeMillis() - lastEmitMs >= REEMIT_INTERVAL_MS) {
                    lastEmitMs = System.currentTimeMillis()
                    runCatching { onJpeg(j) }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        started = false
        runCatching { reemitThread?.interrupt() }; reemitThread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { thread?.quitSafely() }
        codec = null; thread = null; handler = null
        inputQueue.clear(); pendingFrames.clear()
    }

    companion object {
        private const val TAG = "H264ToJpeg"
        private const val REEMIT_INTERVAL_MS = 300L
    }
}
