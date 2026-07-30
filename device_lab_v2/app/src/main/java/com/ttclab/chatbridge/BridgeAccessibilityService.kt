package com.ttclab.chatbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.coroutines.resume

class BridgeAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        val updatedInfo = serviceInfo
        updatedInfo.flags = updatedInfo.flags or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = updatedInfo
        refreshWindowPackage()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString()?.takeIf { it.isNotBlank() }
        if (eventPackage != null) {
            lastEventPackage = eventPackage
            lastEventType = event.eventType
            lastEventAtMs = System.currentTimeMillis()
        }
        refreshWindowPackage()
    }

    fun refreshWindowPackage(): String? {
        val now = System.currentTimeMillis()
        val activeWindowPackage = runCatching {
            windows.orEmpty()
                .sortedWith(
                    compareByDescending<AccessibilityWindowInfo> { it.isActive }
                        .thenByDescending { it.isFocused }
                        .thenByDescending { it.layer }
                )
                .firstNotNullOfOrNull { window ->
                    runCatching { window.root?.packageName?.toString() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                }
        }.getOrNull()

        val rootPackage = runCatching {
            rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull()

        val resolved = activeWindowPackage ?: rootPackage
        if (!resolved.isNullOrBlank()) {
            lastWindowPackage = resolved
            lastWindowAtMs = now
        }
        return resolved
    }

    /**
     * Small accessibility snapshot used together with OCR. Nodes outside the active window are never read.
     */
    fun snapshotWindowTree(maxNodes: Int = 350): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject()
                .put("available", false)
                .put("node_count", 0)
                .put("nodes", JSONArray())

        val nodes = JSONArray()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = queue.removeFirst()
            visited += 1
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()

            if (
                text.isNotBlank() || description.isNotBlank() || viewId.isNotBlank() ||
                node.isClickable || node.isCheckable || node.isEditable || node.isScrollable
            ) {
                nodes.put(
                    JSONObject()
                        .put("depth", depth)
                        .put("class", node.className?.toString() ?: JSONObject.NULL)
                        .put("view_id", if (viewId.isBlank()) JSONObject.NULL else viewId)
                        .put("text", text.take(300))
                        .put("content_description", description.take(300))
                        .put("clickable", node.isClickable)
                        .put("checkable", node.isCheckable)
                        .put("checked", node.isChecked)
                        .put("editable", node.isEditable)
                        .put("enabled", node.isEnabled)
                        .put("focusable", node.isFocusable)
                        .put("focused", node.isFocused)
                        .put("scrollable", node.isScrollable)
                        .put("selected", node.isSelected)
                        .put(
                            "screen_box",
                            JSONObject()
                                .put("left", rect.left)
                                .put("top", rect.top)
                                .put("right", rect.right)
                                .put("bottom", rect.bottom)
                                .put("width", rect.width())
                                .put("height", rect.height())
                        )
                        .put(
                            "normalized_center",
                            JSONObject()
                                .put("x", rect.centerX().toDouble() / resources.displayMetrics.widthPixels.coerceAtLeast(1))
                                .put("y", rect.centerY().toDouble() / resources.displayMetrics.heightPixels.coerceAtLeast(1))
                        )
                )
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> queue.add(child to depth + 1) }
            }
        }

        return JSONObject()
            .put("available", true)
            .put("package", root.packageName?.toString() ?: JSONObject.NULL)
            .put("class", root.className?.toString() ?: JSONObject.NULL)
            .put("visited_count", visited)
            .put("node_count", nodes.length())
            .put("truncated", queue.isNotEmpty())
            .put("nodes", nodes)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 65))
            .build()
        return dispatchAwait(gesture)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(80, 2_000)))
            .build()
        return dispatchAwait(gesture)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    private suspend fun dispatchAwait(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                Handler(Looper.getMainLooper())
            )
            if (!accepted && continuation.isActive) continuation.resume(false)
        }

    companion object {
        @Volatile var instance: BridgeAccessibilityService? = null
            private set
        @Volatile private var lastEventPackage: String? = null
        @Volatile private var lastEventAtMs: Long = 0L
        @Volatile private var lastEventType: Int = 0
        @Volatile private var lastWindowPackage: String? = null
        @Volatile private var lastWindowAtMs: Long = 0L

        fun resolveForegroundSnapshot(): AccessibilityForegroundSnapshot? {
            instance?.refreshWindowPackage()
            val window = lastWindowPackage?.let {
                AccessibilityForegroundSnapshot(it, "accessibility_window", lastWindowAtMs)
            }
            val event = lastEventPackage?.let {
                AccessibilityForegroundSnapshot(
                    it,
                    "accessibility_event_${lastEventType}",
                    lastEventAtMs
                )
            }
            return listOfNotNull(window, event).maxByOrNull { it.updatedAtMs }
        }
    }
}
