package com.ursafe.app;

import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import org.json.JSONObject;

public final class DeviceActionRunner {
    private DeviceActionRunner() {}

    public static void run(Context context, String jobId, JSONObject job) {
        JSONObject result = new JSONObject();
        try {
            String action = job.optString("action", "status");
            result.put("v", 1);
            result.put("job_id", jobId);
            result.put("device_id", BridgeCrypto.getOrCreateDeviceId(context));
            result.put("kind", "device");
            result.put("action", action);
            result.put("created_at_ms", System.currentTimeMillis());
            result.put("screen_active", ScreenCaptureService.isActive());
            result.put("accessibility_ready", UrsafeAccessibilityService.isReady());
            result.put("foreground_package", ScreenFrameStore.foregroundPackage());
            result.put("frame_count", ScreenFrameStore.frameCount());
            result.put("motion", ScreenFrameStore.motion());
            result.put("screen_width", ScreenFrameStore.screenWidth());
            result.put("screen_height", ScreenFrameStore.screenHeight());

            boolean ok;
            switch (action) {
                case "screenshot":
                    byte[] jpeg = ScreenFrameStore.latestJpeg();
                    if (jpeg == null || jpeg.length == 0) {
                        throw new IllegalStateException("ეკრანის კადრი ჯერ ხელმისაწვდომი არ არის");
                    }
                    result.put("screenshot_jpeg_b64",
                            Base64.encodeToString(jpeg, Base64.NO_WRAP));
                    result.put("screenshot_at_ms", ScreenFrameStore.jpegTimestampMs());
                    result.put("status", "completed");
                    result.put("message", "ეკრანის კადრი მიღებულია");
                    break;
                case "tap":
                    ok = UrsafeAccessibilityService.tap(
                            coordinate(job.optDouble("x", -1), ScreenFrameStore.screenWidth()),
                            coordinate(job.optDouble("y", -1), ScreenFrameStore.screenHeight()),
                            job.optLong("duration_ms", 70L));
                    result.put("status", ok ? "completed" : "failed");
                    result.put("message", ok ? "tap გაეშვა" : "Accessibility მზად არ არის");
                    break;
                case "swipe":
                    ok = UrsafeAccessibilityService.swipe(
                            coordinate(job.optDouble("from_x", -1), ScreenFrameStore.screenWidth()),
                            coordinate(job.optDouble("from_y", -1), ScreenFrameStore.screenHeight()),
                            coordinate(job.optDouble("to_x", -1), ScreenFrameStore.screenWidth()),
                            coordinate(job.optDouble("to_y", -1), ScreenFrameStore.screenHeight()),
                            job.optLong("duration_ms", 260L));
                    result.put("status", ok ? "completed" : "failed");
                    result.put("message", ok ? "swipe გაეშვა" : "Accessibility მზად არ არის");
                    break;
                case "back":
                    ok = UrsafeAccessibilityService.back();
                    result.put("status", ok ? "completed" : "failed");
                    result.put("message", ok ? "Back შესრულდა" : "Accessibility მზად არ არის");
                    break;
                case "home":
                    ok = UrsafeAccessibilityService.home();
                    result.put("status", ok ? "completed" : "failed");
                    result.put("message", ok ? "Home შესრულდა" : "Accessibility მზად არ არის");
                    break;
                case "launch":
                    String packageName = job.optString("package", "");
                    ok = launch(context, packageName);
                    result.put("status", ok ? "completed" : "failed");
                    result.put("message", ok ? "აპი გაიხსნა: " + packageName
                            : "აპი ვერ გაიხსნა: " + packageName);
                    break;
                case "status":
                    result.put("status", "completed");
                    result.put("message", "მოწყობილობის სტატუსი მიღებულია");
                    break;
                default:
                    throw new IllegalArgumentException("უცნობი device action: " + action);
            }
        } catch (Exception error) {
            try {
                result.put("status", "failed");
                result.put("message", safe(error.getMessage()));
            } catch (Exception ignored) {}
        }
        BridgeActionReceiver.publishJsonResult(context, jobId, result);
    }

    private static float coordinate(double value, int size) {
        if (value < 0 || size <= 0) throw new IllegalArgumentException("არასწორი კოორდინატია");
        double pixels = value <= 1.0 ? value * size : value;
        return (float) Math.max(0, Math.min(size - 1, pixels));
    }

    private static boolean launch(Context context, String packageName) {
        if (packageName == null || !packageName.matches("[A-Za-z0-9._]+")) return false;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(launch);
        return true;
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "უცნობი შეცდომა" : value;
    }
}
