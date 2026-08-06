package com.ursafe.app;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

public final class DeviceActionRouter {
    private DeviceActionRouter() {}

    public static JSONObject execute(Context context, JSONObject job) throws Exception {
        String action = job.optString("action", "status");
        JSONObject result = new JSONObject();
        result.put("action", action);
        switch (action) {
            case "status":
                result.put("screen_active", ScreenCaptureService.isActive());
                result.put("accessibility_ready", UrsafeAccessibilityService.isReady());
                result.put("frame_count", ScreenFrameStore.frameCount());
                result.put("last_frame_ms", ScreenFrameStore.timestampMs());
                result.put("motion", ScreenFrameStore.motion());
                result.put("foreground_package", ScreenFrameStore.foregroundPackage());
                result.put("message", "device status collected");
                return result;
            case "tap": {
                float x = (float) job.getDouble("x");
                float y = (float) job.getDouble("y");
                long duration = job.optLong("duration_ms", 60L);
                if (!UrsafeAccessibilityService.tap(x, y, duration)) {
                    throw new IllegalStateException("Accessibility service is not enabled");
                }
                result.put("message", "tap dispatched");
                return result;
            }
            case "swipe": {
                float fromX = (float) job.getDouble("from_x");
                float fromY = (float) job.getDouble("from_y");
                float toX = (float) job.getDouble("to_x");
                float toY = (float) job.getDouble("to_y");
                long duration = job.optLong("duration_ms", 300L);
                if (!UrsafeAccessibilityService.swipe(fromX, fromY, toX, toY, duration)) {
                    throw new IllegalStateException("Accessibility service is not enabled");
                }
                result.put("message", "swipe dispatched");
                return result;
            }
            case "back":
                if (!UrsafeAccessibilityService.back()) {
                    throw new IllegalStateException("Accessibility service is not enabled");
                }
                result.put("message", "back dispatched");
                return result;
            case "home":
                if (!UrsafeAccessibilityService.home()) {
                    throw new IllegalStateException("Accessibility service is not enabled");
                }
                result.put("message", "home dispatched");
                return result;
            case "launch": {
                String packageName = job.getString("package");
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
                if (launch == null) throw new IllegalArgumentException("Package cannot be launched: " + packageName);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                result.put("message", "app launch requested");
                result.put("package", packageName);
                return result;
            }
            default:
                throw new IllegalArgumentException("Unsupported device action: " + action);
        }
    }
}
