package com.example.teslamirror.scrcpy

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * scrcpy 컨트롤 메시지 인코딩 (server ← client).
 * 모든 필드는 빅엔디안. scrcpy v4.1 control_msg 포맷 기준.
 */
object ScrcpyProtocol {

    private const val TYPE_INJECT_KEYCODE = 0
    private const val TYPE_INJECT_TEXT = 1
    private const val TYPE_INJECT_TOUCH_EVENT = 2
    private const val TYPE_INJECT_SCROLL_EVENT = 3
    private const val TYPE_BACK_OR_SCREEN_ON = 4
    private const val TYPE_RESET_VIDEO = 17

    // MotionEvent actions
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    // KeyEvent actions
    const val KEY_DOWN = 0
    const val KEY_UP = 1

    private const val BUTTON_PRIMARY = 1  // MotionEvent.BUTTON_PRIMARY

    /**
     * 터치 이벤트. x,y와 videoWidth/videoHeight는 영상 픽셀 좌표계.
     * pointerId는 멀티터치 구분용(단일터치는 0).
     */
    fun injectTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
    ): ByteArray {
        val pressure = if (action == ACTION_UP) 0 else 0xFFFF        // u16 고정소수 1.0
        val buttons = if (action == ACTION_UP) 0 else BUTTON_PRIMARY
        val actionButton = if (action == ACTION_MOVE) 0 else BUTTON_PRIMARY
        val buf = ByteBuffer.allocate(32)
        buf.put(TYPE_INJECT_TOUCH_EVENT.toByte())
        buf.put(action.toByte())
        buf.putLong(pointerId)
        buf.putInt(x)
        buf.putInt(y)
        buf.putShort(videoWidth.toShort())
        buf.putShort(videoHeight.toShort())
        buf.putShort(pressure.toShort())
        buf.putInt(actionButton)
        buf.putInt(buttons)
        return buf.array()
    }

    /** 키코드 주입 (Android KeyEvent keycode). */
    fun injectKeycode(action: Int, keycode: Int, repeat: Int, metaState: Int): ByteArray {
        val buf = ByteBuffer.allocate(14)
        buf.put(TYPE_INJECT_KEYCODE.toByte())
        buf.put(action.toByte())
        buf.putInt(keycode)
        buf.putInt(repeat)
        buf.putInt(metaState)
        return buf.array()
    }

    /** 텍스트 입력 (IME 우회, ASCII 위주). */
    fun injectText(text: String): ByteArray {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 4 + bytes.size)
        buf.put(TYPE_INJECT_TEXT.toByte())
        buf.putInt(bytes.size)
        buf.put(bytes)
        return buf.array()
    }

    /** 뒤로 가기(또는 화면 켜기). */
    fun backOrScreenOn(action: Int): ByteArray =
        byteArrayOf(TYPE_BACK_OR_SCREEN_ON.toByte(), action.toByte())

    /** 인코더에 IDR(키프레임) 재전송 요청 — 뒤늦게 접속한 뷰어 대응. */
    fun resetVideo(): ByteArray = byteArrayOf(TYPE_RESET_VIDEO.toByte())

    /** 스크롤(휠) 이벤트. hScroll/vScroll은 -1.0..1.0 → i16 고정소수로 변환. */
    fun injectScroll(
        x: Int, y: Int, videoWidth: Int, videoHeight: Int,
        hScroll: Float, vScroll: Float,
    ): ByteArray {
        val buf = ByteBuffer.allocate(21)
        buf.put(TYPE_INJECT_SCROLL_EVENT.toByte())
        buf.putInt(x)
        buf.putInt(y)
        buf.putShort(videoWidth.toShort())
        buf.putShort(videoHeight.toShort())
        buf.putShort((hScroll.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        buf.putShort((vScroll.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        buf.putInt(0)  // buttons
        return buf.array()
    }
}
