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
 * scrcpy H.264(Annex-B) → JPEG. 워커 뷰어는 JPEG 데이터채널만 받으므로 폰에서 재인코딩.
 *
 * ByteBuffer 디코드(Surface 없음) — S936N에서 Surface/ImageReader GPU 버퍼는 CPU 접근 불가.
 * config/key 플래그를 올바르게 넣어 디코더가 첫 프레임부터 출력하게 한다.
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

    private val inputQueue = ArrayDeque<Int>()
    /** frame, flags (MediaCodec BUFFER_FLAG_*) */
    private val pendingFrames = ArrayDeque<Pair<ByteArray, Int>>()

    private var imgCount = 0L
    private var jpegCount = 0L
    @Volatile private var lastJpeg: ByteArray? = null
    @Volatile private var lastEmitMs = 0L
    private var reemitThread: Thread? = null
    private var nv21: ByteArray? = null

    fun submitConfig(config: ByteArray) {
        configData = config
        if (!started) {
            ensureStarted(config)
            // csd-0 로 configure 했으면 같은 NAL을 다시 넣을 필요 없음
            return
        }
        // 이미 시작 후 config 패킷 → 디코더에 CODEC_CONFIG 로 투입
        synchronized(this) {
            pendingFrames.addFirst(config to MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        }
        codec?.let { drainInput(it) }
    }

    fun submitFrame(frame: ByteArray, isKey: Boolean) {
        if (!started) ensureStarted(null)
        if (!started) return
        val flags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        synchronized(this) { pendingFrames.add(frame to flags) }
        codec?.let { drainInput(it) }
    }

    /** 뷰어 연결 직후 — 캐시된 마지막 JPEG를 즉시 1장 전송(정적 화면 대응). */
    fun flushLastJpeg() {
        val j = lastJpeg ?: return
        if (!shouldEncode()) return
        lastEmitMs = System.currentTimeMillis()
        runCatching { onJpeg(j) }
        Log.i(TAG, "flushLastJpeg ${j.size}B")
    }

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
                // 일부 디코더는 max-input-size 없으면 첫 키프레임 거부
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
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
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val img = mc.getOutputImage(index)
                            if (img != null) {
                                imgCount++
                                if (imgCount <= 5 || imgCount % 120 == 0L) {
                                    Log.i(TAG, "img#$imgCount ${img.width}x${img.height}")
                                }
                                runCatching { encodeAndCache(img) }.onFailure { Log.w(TAG, "encode", it) }
                                img.close()
                            }
                        }
                    } finally {
                        runCatching { mc.releaseOutputBuffer(index, false) }
                    }
                }
                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "codec error: ${e.diagnosticInfo ?: e.message}")
                }
                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                    Log.i(TAG, "output format $format")
                }
            }, handler)
            c.configure(fmt, null, null, 0)
            c.start()
            codec = c
            started = true
            startReemitLoop()
            Log.i(TAG, "decoder started ${width}x$height csd=${configData?.size ?: 0}B")
        } catch (t: Throwable) {
            Log.e(TAG, "decoder init failed", t)
        }
    }

    @Synchronized
    private fun drainInput(mc: MediaCodec) {
        while (inputQueue.isNotEmpty() && pendingFrames.isNotEmpty()) {
            val index = inputQueue.removeFirst()
            val (frame, flags) = pendingFrames.removeFirst()
            try {
                val buf = mc.getInputBuffer(index) ?: continue
                buf.clear()
                if (frame.size > buf.capacity()) {
                    Log.w(TAG, "frame ${frame.size}B > buf ${buf.capacity()}")
                    continue
                }
                buf.put(frame)
                mc.queueInputBuffer(index, 0, frame.size, System.nanoTime() / 1000, flags)
            } catch (t: Throwable) {
                Log.w(TAG, "queueInput failed", t)
            }
        }
    }

    private fun encodeAndCache(img: Image) {
        val w = img.width
        val h = img.height
        val out = nv21?.takeIf { it.size == w * h * 3 / 2 } ?: ByteArray(w * h * 3 / 2).also { nv21 = it }
        yuv420ToNv21(img, out)
        baos.reset()
        YuvImage(out, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, baos)
        val bytes = baos.toByteArray()
        jpegCount++
        if (jpegCount <= 5 || jpegCount % 120 == 0L) Log.i(TAG, "jpeg#$jpegCount ${bytes.size}B send=${shouldEncode()}")
        lastJpeg = bytes
        if (shouldEncode()) {
            lastEmitMs = System.currentTimeMillis()
            onJpeg(bytes)
        }
    }

    private fun yuv420ToNv21(image: Image, out: ByteArray) {
        val w = image.width
        val h = image.height
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        var pos = 0
        val yBuf = yP.buffer
        val yRow = yP.rowStride
        val yRowTmp = ByteArray(yRow)
        for (row in 0 until h) {
            yBuf.position(row * yRow)
            val n = minOf(yRow, yBuf.remaining())
            yBuf.get(yRowTmp, 0, n)
            System.arraycopy(yRowTmp, 0, out, pos, w)
            pos += w
        }
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val uRow = uP.rowStride
        val vRow = vP.rowStride
        val uPix = uP.pixelStride
        val vPix = vP.pixelStride
        val cw = w / 2
        val ch = h / 2
        for (row in 0 until ch) {
            var uIdx = row * uRow
            var vIdx = row * vRow
            for (col in 0 until cw) {
                out[pos++] = vBuf.get(vIdx)
                out[pos++] = uBuf.get(uIdx)
                uIdx += uPix
                vIdx += vPix
            }
        }
    }

    private fun startReemitLoop() {
        reemitThread = Thread {
            while (started) {
                try {
                    Thread.sleep(REEMIT_INTERVAL_MS)
                } catch (_: Throwable) {
                    break
                }
                val j = lastJpeg ?: continue
                if (!shouldEncode()) continue
                if (System.currentTimeMillis() - lastEmitMs >= REEMIT_INTERVAL_MS) {
                    lastEmitMs = System.currentTimeMillis()
                    runCatching { onJpeg(j) }
                }
            }
        }.apply { isDaemon = true; name = "H264JpegReemit"; start() }
    }

    fun stop() {
        started = false
        runCatching { reemitThread?.interrupt() }
        reemitThread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { thread?.quitSafely() }
        codec = null
        thread = null
        handler = null
        synchronized(this) {
            inputQueue.clear()
            pendingFrames.clear()
        }
        lastJpeg = null
    }

    companion object {
        private const val TAG = "H264ToJpeg"
        private const val REEMIT_INTERVAL_MS = 300L
    }
}
