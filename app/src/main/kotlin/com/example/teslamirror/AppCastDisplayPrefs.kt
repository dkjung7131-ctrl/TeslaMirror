package com.example.teslamirror

import android.content.Context
import android.util.Log
import kotlin.math.roundToInt

/**
 * 앱 모드 가상 디스플레이 해상도.
 * - fillTesla=true(기본): 마지막 뷰어 창 비율에 맞춤 (여백 최소)
 * - false: 기본 1280×800
 *
 * 주의: 가로/세로를 따로 coerce 하면 비율이 깨져 상하+좌우 여백이 동시에 커질 수 있음.
 */
object AppCastDisplayPrefs {
    private const val TAG = "AppCastDisplay"
    private const val PREFS = "appcast_display"
    private const val KEY_FILL = "fill_tesla"
    private const val KEY_VW = "viewer_w"
    private const val KEY_VH = "viewer_h"

    const val DEFAULT_W = 1280
    const val DEFAULT_H = 800

    /** 차량/가로 브라우저로 볼 법한 비율만 학습 (세로·극단값 제외) */
    private const val MIN_ASPECT = 1.35f  // ~4:3 가로
    private const val MAX_ASPECT = 2.40f  // ~21:9

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 기본 true — 테슬라 화면에 꽉 채우기 */
    fun isFillEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FILL, true)

    fun setFillEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FILL, enabled).apply()
    }

    fun clearViewport(context: Context) {
        prefs(context).edit().remove(KEY_VW).remove(KEY_VH).apply()
        Log.i(TAG, "viewport cleared")
    }

    /**
     * 뷰포트 저장. 세로 화면·이상 비율은 무시 (잘못 학습하면 여백만 커짐).
     */
    fun saveViewport(context: Context, width: Int, height: Int) {
        if (width < 200 || height < 150) return
        // 항상 가로 기준으로 정규화
        val (vw, vh) = if (width >= height) width to height else height to width
        val aspect = vw.toFloat() / vh.toFloat()
        if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) {
            Log.w(TAG, "viewport rejected ${vw}x$vh aspect=${"%.3f".format(aspect)}")
            return
        }
        prefs(context).edit()
            .putInt(KEY_VW, vw)
            .putInt(KEY_VH, vh)
            .apply()
        Log.i(TAG, "viewport saved ${vw}x$vh aspect=${"%.3f".format(aspect)}")
    }

    fun hasViewport(context: Context): Boolean {
        val p = prefs(context)
        val w = p.getInt(KEY_VW, 0)
        val h = p.getInt(KEY_VH, 0)
        if (w < 200 || h < 150) return false
        val a = w.toFloat() / h.toFloat()
        return a in MIN_ASPECT..MAX_ASPECT
    }

    fun viewerSize(context: Context): Pair<Int, Int>? {
        if (!hasViewport(context)) return null
        val p = prefs(context)
        return p.getInt(KEY_VW, 0) to p.getInt(KEY_VH, 0)
    }

    /**
     * scrcpy new_display 용 짝수 해상도.
     * 비율을 유지한 채 1280×800 박스(및 상한) 안에 맞춤 — 축별 coerce 금지.
     */
    fun resolveDisplaySize(context: Context): Pair<Int, Int> {
        if (!isFillEnabled(context)) {
            return DEFAULT_W to DEFAULT_H
        }
        val (vw, vh) = viewerSize(context) ?: return DEFAULT_W to DEFAULT_H
        val aspect = vw.toFloat() / vh.toFloat()
        // 기본 박스 안에 동일 비율로 최대 크기 (1280×800 envelope)
        // → 16:9 뷰포트면 1280×720, 16:10이면 1280×800
        val (outW, outH) = fitAspectInBox(aspect, DEFAULT_W, DEFAULT_H)
        Log.i(TAG, "resolve fill ${outW}x$outH from viewer ${vw}x$vh (a=${"%.3f".format(aspect)})")
        return outW to outH
    }

    /**
     * aspect = width/height 를 유지하며 maxW×maxH 안에 최대한 크게.
     */
    fun fitAspectInBox(aspect: Float, maxW: Int, maxH: Int): Pair<Int, Int> {
        var w = maxW
        var h = (w / aspect).roundToInt()
        if (h > maxH) {
            h = maxH
            w = (h * aspect).roundToInt()
        }
        w = alignEven(w.coerceAtLeast(640))
        h = alignEven(h.coerceAtLeast(360))
        // even 정렬 후 비율 미세 보정 (한 축만 2px 흔들림)
        val a2 = w.toFloat() / h.toFloat()
        if (kotlin.math.abs(a2 - aspect) > 0.02f) {
            h = alignEven((w / aspect).roundToInt().coerceAtLeast(360))
        }
        return w to h
    }

    fun summaryLabel(context: Context): String {
        val (w, h) = resolveDisplaySize(context)
        return if (isFillEnabled(context) && hasViewport(context)) {
            val (vw, vh) = viewerSize(context)!!
            "${w}×${h} (맞춤 ${vw}×${vh})"
        } else if (isFillEnabled(context)) {
            "${w}×${h} (기본 · 접속 후 학습)"
        } else {
            "${w}×${h} (고정)"
        }
    }

    private fun alignEven(n: Int): Int = n and 1.inv()
}
