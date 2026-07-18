package com.example.teslamirror.capture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * scrcpy가 주는 H.264(Annex-B) 스트림을 디코드해 JPEG로 변환한다.
 * 앱 모드 뷰어(테슬라 브라우저)는 전체화면과 동일한 **JPEG 데이터채널** 뷰어라,
 * H.264를 그대로 못 그리므로 폰에서 디코드→JPEG 재인코딩한다(WebCodecs 미지원 회피).
 *
 * 경로: MediaCodec(video/avc) → ImageReader(YUV_420_888) → NV21 → YuvImage.compressToJpeg.
 * [shouldEncode]가 false면 디코더 출력은 비우되 JPEG 인코딩/전송은 건너뛴다(뷰어 없음/백프레셔).
 *
 * config(SPS/PPS) 패킷을 먼저 [submitConfig]로 준 뒤 프레임을 [submitFrame]으로 넣는다.
 */
class H264ToJpeg(
    private val width: Int,
    private val height: Int,
    private val quality: Int = 60,
    private val shouldEncode: () -> Boolean = { true },
    private val onJpeg: (ByteArray) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var started = false
    @Volatile private var configData: ByteArray? = null
    private val baos = ByteArrayOutputStream(128 * 1024)

    /** 첫 config(SPS/PPS Annex-B) 수신 시 디코더 구성·기동. */
    @Synchronized
    fun submitConfig(config: ByteArray) {
        configData = config
        if (started) return
        try {
            val t = HandlerThread("H264Decode").also { it.start() }
            thread = t
            handler = Handler(t.looper)

            val r = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
            r.setOnImageAvailableListener({ ir ->
                val img = try { ir.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
                try {
                    if (shouldEncode()) encodeAndEmit(img)
                } catch (t: Throwable) {
                    Log.w(TAG, "encode failed", t)
                } finally {
                    img.close()
                }
            }, handler)
            reader = r

            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config))
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    synchronized(this@H264ToJpeg) { inputQueue.add(index) }
                    drainInput(mc)
                }
                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    // ImageReader surface로 렌더 → onImageAvailable에서 처리
                    runCatching { mc.releaseOutputBuffer(index, true) }
                }
                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "codec error: ${e.message}")
                }
                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {}
            }, handler)
            c.configure(fmt, r.surface, null, 0)
            c.start()
            codec = c
            started = true
            Log.i(TAG, "decoder started ${width}x$height")
        } catch (t: Throwable) {
            Log.e(TAG, "decoder init failed", t)
        }
    }

    /** H.264 프레임(Annex-B, key/delta) 투입. */
    fun submitFrame(frame: ByteArray, isKey: Boolean) {
        if (!started) return
        synchronized(this) { pendingFrames.add(frame to isKey) }
        codec?.let { drainInput(it) }
    }

    // MediaCodec 비동기: 사용 가능한 입력 버퍼 인덱스 ↔ 대기 프레임을 매칭.
    private val inputQueue = ArrayDeque<Int>()
    private val pendingFrames = ArrayDeque<Pair<ByteArray, Boolean>>()

    @Synchronized
    private fun drainInput(mc: MediaCodec) {
        while (inputQueue.isNotEmpty() && pendingFrames.isNotEmpty()) {
            val index = inputQueue.removeFirst()
            val (frame, _) = pendingFrames.removeFirst()
            try {
                val buf = mc.getInputBuffer(index) ?: continue
                buf.clear()
                buf.put(frame)
                mc.queueInputBuffer(index, 0, frame.size, System.nanoTime() / 1000, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "queueInput failed", t)
            }
        }
    }

    private fun encodeAndEmit(img: Image) {
        val nv21 = yuv420ToNv21(img)
        baos.reset()
        val yuv = YuvImage(nv21, ImageFormat.NV21, img.width, img.height, null)
        yuv.compressToJpeg(Rect(0, 0, img.width, img.height), quality, baos)
        onJpeg(baos.toByteArray())
    }

    fun stop() {
        started = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { reader?.close() }
        runCatching { thread?.quitSafely() }
        codec = null; reader = null; thread = null; handler = null
        inputQueue.clear(); pendingFrames.clear()
    }

    /** YUV_420_888(스트라이드 고려) → NV21(Y plane + 인터리브 VU). */
    private fun yuv420ToNv21(image: Image): ByteArray {
        val w = image.width; val h = image.height
        val out = ByteArray(w * h * 3 / 2)
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]

        // Y
        var pos = 0
        val yBuf = yP.buffer; val yRow = yP.rowStride
        for (row in 0 until h) {
            val start = row * yRow
            yBuf.position(start)
            yBuf.get(out, pos, w)
            pos += w
        }
        // VU 인터리브 (NV21 = V,U 순)
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
                uIdx += uPix
                vIdx += vPix
            }
        }
        return out
    }

    companion object {
        private const val TAG = "H264ToJpeg"
    }
}
