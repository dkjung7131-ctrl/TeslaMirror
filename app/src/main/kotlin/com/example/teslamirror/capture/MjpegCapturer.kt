package com.example.teslamirror.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/**
 * Captures frames via ImageReader, converts to Bitmap, JPEG-compresses them,
 * and pushes encoded bytes to onFrame at the desired fps.
 *
 * Two-slot pipeline (no per-frame copy, but static frames keep flowing):
 *   - producer (ImageReader callback): writes new bitmap to `pendingBitmap`
 *   - consumer (loop): swaps pending into `currentBitmap` if available, then compresses current
 *     (re-uses currentBitmap if no new frame, so static screens still broadcast)
 */
class MjpegCapturer(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 15,
    private val quality: Int = 65,
    private val onFrame: (ByteArray) -> Unit
) {

    private val reader: ImageReader = ImageReader.newInstance(
        width, height, PixelFormat.RGBA_8888, 2
    )
    val surface: Surface get() = reader.surface

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var loop: Job? = null

    @Volatile private var pendingBitmap: Bitmap? = null
    @Volatile private var currentBitmap: Bitmap? = null

    fun start(scope: CoroutineScope) {
        thread = HandlerThread("MjpegCapture").also { it.start() }
        handler = Handler(thread!!.looper)

        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * width
                val bmpW = width + rowPadding / pixelStride
                val bmp = Bitmap.createBitmap(bmpW, height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (rowPadding == 0) bmp
                    else Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
                synchronized(this) {
                    pendingBitmap?.recycle()  // 컨슈머가 못 가져간 이전 pending은 버림
                    pendingBitmap = cropped
                }
            } finally {
                img.close()
            }
        }, handler)

        val frameInterval = 1000L / fps
        loop = scope.launch(Dispatchers.Default) {
            val baos = ByteArrayOutputStream(64 * 1024)
            while (isActive) {
                val start = System.currentTimeMillis()
                val bmp = synchronized(this@MjpegCapturer) {
                    // 새 프레임이 와있으면 current를 교체 (이전 current 회수)
                    val pending = pendingBitmap
                    if (pending != null) {
                        currentBitmap?.recycle()
                        currentBitmap = pending
                        pendingBitmap = null
                    }
                    currentBitmap  // 새 프레임 없으면 같은 비트맵 재송신
                }
                if (bmp != null) {
                    baos.reset()
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                    onFrame(baos.toByteArray())
                }
                val elapsed = System.currentTimeMillis() - start
                val sleep = frameInterval - elapsed
                if (sleep > 0) delay(sleep)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        try { reader.close() } catch (_: Throwable) {}
        try { thread?.quitSafely() } catch (_: Throwable) {}
        synchronized(this) {
            pendingBitmap?.recycle()
            currentBitmap?.recycle()
            pendingBitmap = null
            currentBitmap = null
        }
    }
}
