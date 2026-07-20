package com.example.teslamirror.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 앱 모드 전용: 포커스가 편집 가능한 입력란(EditText 등)으로 가면
 * [listener]에 true, 벗어나면 false.
 *
 * 가상 디스플레이의 IME는 테슬라 브라우저에 안 뜨므로, 뷰어가 HTML input 으로
 * 테슬라 키보드를 대신 띄운다.
 */
class ImeWatchService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var lastEditable = false
    private var lastNotifyMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 80
        }
        Log.i(TAG, "ImeWatchService connected")
        // 연결 직후 한 번 스캔
        main.post { evaluateAndNotify() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (listener == null) return
        // 너무 잦은 content change 스로틀
        val now = System.currentTimeMillis()
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            now - lastNotifyMs < 120
        ) return
        evaluateAndNotify()
    }

    override fun onInterrupt() {}

    private fun evaluateAndNotify() {
        val editable = hasEditableFocus()
        // 상태가 바뀔 때만 알림 — content change 스팸 방지
        if (editable == lastEditable) return
        lastEditable = editable
        lastNotifyMs = System.currentTimeMillis()
        Log.i(TAG, "editableFocus=$editable")
        runCatching { listener?.invoke(editable) }
    }

    private fun hasEditableFocus(): Boolean {
        // 1) 입력 포커스 노드
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { n ->
            try {
                if (isEditable(n)) return true
            } finally {
                n.recycle()
            }
        }
        // 2) 활성 윈도우 트리에서 focused+editable
        val root = rootInActiveWindow ?: return false
        try {
            return findEditableFocused(root)
        } finally {
            root.recycle()
        }
    }

    private fun findEditableFocused(node: AccessibilityNodeInfo): Boolean {
        if (node.isFocused && isEditable(node)) return true
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                if (findEditableFocused(c)) return true
            } finally {
                c.recycle()
            }
        }
        return false
    }

    private fun isEditable(n: AccessibilityNodeInfo): Boolean {
        if (n.isEditable) return true
        val cls = n.className?.toString() ?: return false
        return cls.contains("EditText", ignoreCase = true) ||
            cls.contains("AutoComplete", ignoreCase = true) ||
            cls.contains("TextField", ignoreCase = true) ||
            cls.contains("SearchView", ignoreCase = true)
    }

    companion object {
        private const val TAG = "ImeWatchService"

        /** AppCast 가 등록. true=입력란 포커스, false=해제. */
        @Volatile
        var listener: ((Boolean) -> Unit)? = null

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            if (!am.isEnabled) return false
            val mine = ComponentName(context, ImeWatchService::class.java)
            // 1) 시스템 리스트 (가장 정확)
            val list = runCatching {
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            }.getOrNull().orEmpty()
            if (list.any { info ->
                    val si = info.resolveInfo?.serviceInfo ?: return@any false
                    ComponentName(si.packageName, si.name) == mine
                }
            ) return true
            // 2) Secure 설정 문자열 (포맷 변형 대비 contains)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val flat = mine.flattenToString()
            val short = mine.flattenToShortString()
            return enabled.split(':', ';').any { entry ->
                val e = entry.trim()
                e.equals(flat, true) || e.equals(short, true) ||
                    e.endsWith("/${mine.className}", true) ||
                    e.endsWith("/.input.ImeWatchService", true)
            }
        }
    }
}
