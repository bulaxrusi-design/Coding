package com.ttclab.chatbridge

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

data class AccessibilityForegroundSnapshot(
    val packageName: String?,
    val source: String,
    val updatedAtMs: Long
)

data class ForegroundResolution(
    val packageName: String?,
    val source: String,
    val usageAccessGranted: Boolean,
    val accessibilityConnected: Boolean,
    val ageMs: Long?
)

object ForegroundResolver {
    private const val ACCESSIBILITY_FRESH_MS = 10_000L
    private const val USAGE_LOOKBACK_MS = 30_000L

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun resolve(context: Context): ForegroundResolution {
        val now = System.currentTimeMillis()
        val usageGranted = hasUsageAccess(context)
        val accessibility = BridgeAccessibilityService.resolveForegroundSnapshot()
        val accessibilityAge = accessibility?.updatedAtMs?.let { (now - it).coerceAtLeast(0L) }

        if (!accessibility?.packageName.isNullOrBlank() &&
            accessibilityAge != null && accessibilityAge <= ACCESSIBILITY_FRESH_MS
        ) {
            return ForegroundResolution(
                packageName = accessibility?.packageName,
                source = accessibility?.source ?: "accessibility",
                usageAccessGranted = usageGranted,
                accessibilityConnected = true,
                ageMs = accessibilityAge
            )
        }

        if (usageGranted) {
            resolveFromUsageStats(context, now)?.let { (packageName, timestamp) ->
                return ForegroundResolution(
                    packageName = packageName,
                    source = "usage_stats",
                    usageAccessGranted = true,
                    accessibilityConnected = BridgeAccessibilityService.instance != null,
                    ageMs = (now - timestamp).coerceAtLeast(0L)
                )
            }
        }

        if (!accessibility?.packageName.isNullOrBlank()) {
            return ForegroundResolution(
                packageName = accessibility?.packageName,
                source = "${accessibility?.source ?: "accessibility"}_stale",
                usageAccessGranted = usageGranted,
                accessibilityConnected = true,
                ageMs = accessibilityAge
            )
        }

        resolveFromRunningProcesses(context)?.let { packageName ->
            return ForegroundResolution(
                packageName = packageName,
                source = "running_process_fallback",
                usageAccessGranted = usageGranted,
                accessibilityConnected = BridgeAccessibilityService.instance != null,
                ageMs = null
            )
        }

        return ForegroundResolution(
            packageName = null,
            source = "unresolved",
            usageAccessGranted = usageGranted,
            accessibilityConnected = BridgeAccessibilityService.instance != null,
            ageMs = null
        )
    }

    private fun resolveFromUsageStats(context: Context, now: Long): Pair<String, Long>? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(now - USAGE_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var packageName: String? = null
        var timestamp = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val foregroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            if (foregroundEvent && event.timeStamp >= timestamp && !event.packageName.isNullOrBlank()) {
                packageName = event.packageName
                timestamp = event.timeStamp
            }
        }
        return packageName?.let { it to timestamp }
    }

    private fun resolveFromRunningProcesses(context: Context): String? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.runningAppProcesses
            ?.asSequence()
            ?.filter { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
            ?.map { it.processName.substringBefore(':') }
            ?.firstOrNull { it != context.packageName && it.isNotBlank() }
    }
}
