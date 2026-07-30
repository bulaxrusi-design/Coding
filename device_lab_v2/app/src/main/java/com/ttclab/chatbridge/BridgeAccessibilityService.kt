package com.ttclab.chatbridge

import android.accessibilityservice.AccessibilityService
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
        refreshForegroundPackage()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString()?.takeIf { it.isNotBlank() }
        if (eventPackage != null) {
            foregroundPackage = eventPackage
            lastForegroundUpdateMs = System.currentTimeMillis()
        } else {
            refreshForegroundPackage()
        }
    }

    /**
     * Accessibility events are sometimes sparse on older Samsung builds. Resolve the
     * current package from the active/focused accessibility window as a fallback.
     */
    fun refreshForegroundPackage(): String? {
        val candidates = runCatching {
            windows.orEmpty()
                .sortedWith(
                    compareByDescending<AccessibilityWindowInfo> { it.isActive }
                        .thenByDescending { it.isFocused }
                        .thenByDescending { it.layer }
                )
                .mapNotNull { window ->
                    runCatching { window.root?.packageName?.toString() }.getOrNull()
                }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

        val rootPackage = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val resolved = candidates.firstOrNull() ?: rootPackage
        if (!resolved.isNullOrBlank()) {
            foregroundPackage = resolved
            lastForegroundUpdateMs = System.currentTimeMillis()
        }
        return foregroundPackage
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
        @Volatile var foregroundPackage: String? = null
        @Volatile var lastForegroundUpdateMs: Long = 0L

        fun resolveForegroundPackage(): String? =
            instance?.refreshForegroundPackage() ?: foregroundPackage
    }
}
