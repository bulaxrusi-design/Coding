package com.ttclab.chatbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
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

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchAwait(gesture)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100, 2_000)))
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
