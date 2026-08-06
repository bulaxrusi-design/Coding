package com.ursafe.app;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

public final class DeviceActionRunner {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_SEQUENCE_STEPS = 16;

    private DeviceActionRunner() {}

    public static void run(Context context, String jobId, JSONObject job) {
        String action = job.optString("action", "status");
        if ("sequence".equals(action)) {
            runSequence(context, jobId, job);
            return;
        }

        JSONObject result = baseResult(context, jobId, action);
        boolean mutating = isMutating(action);
        boolean accepted = false;
        try {
            switch (action) {
                case "screenshot":
                case "status":
                    result.put("status", "completed");
                    result.put("message", "screenshot".equals(action)
                            ? "ეკრანის კადრი მიღებულია"
                            : "მოწყობილობის სტატუსი მიღებულია");
                    break;
                case "tap":
                    accepted = UrsafeAccessibilityService.tap(
                            coordinate(job.optDouble("x", -1), ScreenFrameStore.screenWidth()),
                            coordinate(job.optDouble("y", -1), ScreenFrameStore.screenHeight()),
                            bounded(job.optLong("duration_ms", 70L), 40L, 1500L));
                    setOutcome(result, accepted, "tap გაეშვა");
                    break;
                case "swipe":
                    accepted = UrsafeAccessibilityService.swipe(
                            coordinate(job.optDouble("from_x", -1), ScreenFrameStore.screenWidth()),
                            coordinate(job.optDouble("from_y", -1), ScreenFrameStore.screenHeight()),
                            coordinate(job.optDouble("to_x", -1), ScreenFrameStore.screenWidth()),
                            coordinate(job.optDouble("to_y", -1), ScreenFrameStore.screenHeight()),
                            bounded(job.optLong("duration_ms", 260L), 100L, 3000L));
                    setOutcome(result, accepted, "swipe გაეშვა");
                    break;
                case "back":
                    accepted = UrsafeAccessibilityService.back();
                    setOutcome(result, accepted, "Back შესრულდა");
                    break;
                case "home":
                    accepted = UrsafeAccessibilityService.home();
                    setOutcome(result, accepted, "Home შესრულდა");
                    break;
                case "launch":
                    accepted = launch(context, job.optString("package", ""));
                    setOutcome(result, accepted, "აპი გაიხსნა");
                    break;
                default:
                    throw new IllegalArgumentException("უცნობი device action: " + action);
            }
            if (accepted && mutating && LiveControlSession.isActive(context)) {
                LiveControlSession.recordAction(context, job);
            }
        } catch (Exception error) {
            safePut(result, "status", "failed");
            safePut(result, "message", safe(error.getMessage()));
        }

        boolean withScreenshot = job.optBoolean("return_screenshot",
                LiveControlSession.isActive(context) || "screenshot".equals(action));
        long waitMs = mutating
                ? bounded(job.optLong("wait_ms", 480L), 100L, 2500L) : 0L;
        publishAfter(context, jobId, result, waitMs, withScreenshot);
    }

    private static void runSequence(Context context, String jobId, JSONObject job) {
        JSONObject result = baseResult(context, jobId, "sequence");
        JSONArray steps = job.optJSONArray("steps");
        if (steps == null || steps.length() == 0) {
            safePut(result, "status", "failed");
            safePut(result, "message", "sequence ცარიელია");
            publishAfter(context, jobId, result, 0L, true);
            return;
        }
        if (steps.length() > MAX_SEQUENCE_STEPS) {
            safePut(result, "status", "failed");
            safePut(result, "message", "sequence მაქსიმუმ " + MAX_SEQUENCE_STEPS + " მოქმედებაა");
            publishAfter(context, jobId, result, 0L, true);
            return;
        }
        executeSequenceStep(context, jobId, job, result, steps, 0, new JSONArray());
    }

    private static void executeSequenceStep(Context context, String jobId, JSONObject parent,
                                            JSONObject result, JSONArray steps, int index,
                                            JSONArray outcomes) {
        if (index >= steps.length()) {
            safePut(result, "status", "completed");
            safePut(result, "message", "sequence შესრულდა");
            safePut(result, "steps", outcomes);
            long wait = bounded(parent.optLong("wait_ms", 500L), 100L, 2500L);
            publishAfter(context, jobId, result, wait, true);
            return;
        }

        JSONObject step = steps.optJSONObject(index);
        if (step == null) {
            failSequence(context, jobId, result, outcomes, "არასწორი step: " + index);
            return;
        }
        String action = step.optString("action", "");
        boolean accepted;
        try {
            switch (action) {
                case "tap":
                    accepted = UrsafeAccessibilityService.tap(
                            coordinate(step.optDouble("x", -1), ScreenFrameStore.screenWidth()),
                            coordinate(step.optDouble("y", -1), ScreenFrameStore.screenHeight()),
                            bounded(step.optLong("duration_ms", 65L), 40L, 1500L));
                    break;
                case "swipe":
                    accepted = UrsafeAccessibilityService.swipe(
                            coordinate(step.optDouble("from_x", -1), ScreenFrameStore.screenWidth()),
                            coordinate(step.optDouble("from_y", -1), ScreenFrameStore.screenHeight()),
                            coordinate(step.optDouble("to_x", -1), ScreenFrameStore.screenWidth()),
                            coordinate(step.optDouble("to_y", -1), ScreenFrameStore.screenHeight()),
                            bounded(step.optLong("duration_ms", 240L), 100L, 3000L));
                    break;
                case "back":
                    accepted = UrsafeAccessibilityService.back();
                    break;
                default:
                    throw new IllegalArgumentException("sequence action დაუშვებელია: " + action);
            }
        } catch (Exception error) {
            failSequence(context, jobId, result, outcomes, safe(error.getMessage()));
            return;
        }

        JSONObject outcome = new JSONObject();
        safePut(outcome, "index", index);
        safePut(outcome, "action", action);
        safePut(outcome, "accepted", accepted);
        outcomes.put(outcome);
        if (!accepted) {
            failSequence(context, jobId, result, outcomes,
                    "Accessibility-მ step არ მიიღო: " + index);
            return;
        }
        if (LiveControlSession.isActive(context)) {
            LiveControlSession.recordAction(context, step);
        }
        long delay = bounded(step.optLong("delay_ms", 260L), 80L, 2500L);
        MAIN.postDelayed(() -> executeSequenceStep(context, jobId, parent,
                result, steps, index + 1, outcomes), delay);
    }

    private static void failSequence(Context context, String jobId, JSONObject result,
                                     JSONArray outcomes, String message) {
        safePut(result, "status", "failed");
        safePut(result, "message", message);
        safePut(result, "steps", outcomes);
        publishAfter(context, jobId, result, 250L, true);
    }

    private static JSONObject baseResult(Context context, String jobId, String action) {
        JSONObject result = new JSONObject();
        safePut(result, "v", 1);
        safePut(result, "job_id", jobId);
        safePut(result, "device_id", BridgeCrypto.getOrCreateDeviceId(context));
        safePut(result, "kind", "device");
        safePut(result, "action", action);
        safePut(result, "created_at_ms", System.currentTimeMillis());
        appendState(context, result);
        return result;
    }

    private static void publishAfter(Context context, String jobId, JSONObject result,
                                     long waitMs, boolean withScreenshot) {
        Runnable publish = () -> {
            appendState(context, result);
            if (withScreenshot) appendScreenshot(result);
            BridgeActionReceiver.publishJsonResult(context, jobId, result);
        };
        if (waitMs <= 0L) publish.run();
        else MAIN.postDelayed(publish, waitMs);
    }

    private static void appendState(Context context, JSONObject result) {
        safePut(result, "screen_active", ScreenCaptureService.isActive());
        safePut(result, "accessibility_ready", UrsafeAccessibilityService.isReady());
        safePut(result, "foreground_package", ScreenFrameStore.foregroundPackage());
        safePut(result, "frame_count", ScreenFrameStore.frameCount());
        safePut(result, "motion", ScreenFrameStore.motion());
        safePut(result, "screen_width", ScreenFrameStore.screenWidth());
        safePut(result, "screen_height", ScreenFrameStore.screenHeight());
        safePut(result, "live_control", LiveControlSession.status(context));
    }

    private static void appendScreenshot(JSONObject result) {
        byte[] jpeg = ScreenFrameStore.latestJpeg();
        if (jpeg == null || jpeg.length == 0) {
            safePut(result, "screenshot_error", "ეკრანის კადრი ჯერ ხელმისაწვდომი არ არის");
            return;
        }
        safePut(result, "screenshot_jpeg_b64",
                Base64.encodeToString(jpeg, Base64.NO_WRAP));
        safePut(result, "screenshot_at_ms", ScreenFrameStore.jpegTimestampMs());
    }

    private static void setOutcome(JSONObject result, boolean ok, String success) {
        safePut(result, "status", ok ? "completed" : "failed");
        safePut(result, "message", ok ? success : "Accessibility/აპი მზად არ არის");
    }

    private static boolean isMutating(String action) {
        return !"status".equals(action) && !"screenshot".equals(action);
    }

    private static float coordinate(double value, int size) {
        if (value < 0 || size <= 0) throw new IllegalArgumentException("არასწორი კოორდინატია");
        double pixels = value <= 1.0 ? value * size : value;
        return (float) Math.max(0, Math.min(size - 1, pixels));
    }

    private static long bounded(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean launch(Context context, String packageName) {
        if (packageName == null || !packageName.matches("[A-Za-z0-9._]+")) return false;
        if (LiveControlSession.isActive(context)
                && !packageName.equals(LiveControlSession.targetPackage(context))) return false;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(launch);
        return true;
    }

    private static void safePut(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception ignored) {}
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "უცნობი შეცდომა" : value;
    }
}
