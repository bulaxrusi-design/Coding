package com.ursafe.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

public final class LiveControlSession {
    private static final String PREF_ACTIVE = "live_control_active";
    private static final String PREF_LABEL = "live_control_label";
    private static final String PREF_PACKAGE = "live_control_package";
    private static final String PREF_SESSION = "live_control_session_id";
    private static final String PREF_EXPIRES = "live_control_expires_at";
    private static final String PREF_ACTIONS = "live_control_actions";
    private static final String PREF_LAST_ACTION = "live_control_last_action_at";
    private static final String CHANNEL = "ursafe_live_control";
    private static final int NOTIFICATION_ID = 6900;
    private static final int MAX_ACTIONS = 2500;
    private static final long MIN_ACTION_GAP_MS = 90L;

    private LiveControlSession() {}

    public static synchronized String start(Context context, String label,
                                            String packageName, int minutes) {
        if (packageName == null || !packageName.matches("[A-Za-z0-9._]+")) {
            return "ჯერ აირჩიე თამაში.";
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return "არჩეული თამაში ვერ გაიხსნა.";
        if (!ScreenCaptureService.isActive()) return "ჯერ ჩართე ეკრანის დაკვირვება.";
        if (!UrsafeAccessibilityService.isReady()) return "ჯერ ჩართე Accessibility.";

        int boundedMinutes = Math.max(5, Math.min(120, minutes));
        String sessionId = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toLowerCase(Locale.ROOT);
        long expiresAt = System.currentTimeMillis() + boundedMinutes * 60_000L;
        BridgeCrypto.prefs(context).edit()
                .putBoolean(PREF_ACTIVE, true)
                .putString(PREF_LABEL, label == null ? packageName : label)
                .putString(PREF_PACKAGE, packageName)
                .putString(PREF_SESSION, sessionId)
                .putLong(PREF_EXPIRES, expiresAt)
                .putInt(PREF_ACTIONS, 0)
                .putLong(PREF_LAST_ACTION, 0L)
                .apply();
        showNotification(context);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(launch);
        return "Live Control ჩაირთო " + boundedMinutes + " წუთით.";
    }

    public static synchronized void stop(Context context, String reason) {
        BridgeCrypto.prefs(context).edit()
                .putBoolean(PREF_ACTIVE, false)
                .putString("live_control_stop_reason", reason == null ? "stopped" : reason)
                .putLong("live_control_stopped_at", System.currentTimeMillis())
                .apply();
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(NOTIFICATION_ID);
    }

    public static boolean isActive(Context context) {
        boolean active = BridgeCrypto.prefs(context).getBoolean(PREF_ACTIVE, false);
        if (!active) return false;
        long expires = expiresAt(context);
        if (expires <= System.currentTimeMillis()) {
            stop(context, "expired");
            return false;
        }
        if (!ScreenCaptureService.isActive()) return false;
        return true;
    }

    public static String targetPackage(Context context) {
        return BridgeCrypto.prefs(context).getString(PREF_PACKAGE, "");
    }

    public static String targetLabel(Context context) {
        return BridgeCrypto.prefs(context).getString(PREF_LABEL, "");
    }

    public static String sessionId(Context context) {
        return BridgeCrypto.prefs(context).getString(PREF_SESSION, "");
    }

    public static long expiresAt(Context context) {
        return BridgeCrypto.prefs(context).getLong(PREF_EXPIRES, 0L);
    }

    public static int actionCount(Context context) {
        return BridgeCrypto.prefs(context).getInt(PREF_ACTIONS, 0);
    }

    public static long remainingMs(Context context) {
        return Math.max(0L, expiresAt(context) - System.currentTimeMillis());
    }

    public static synchronized boolean authorize(Context context, JSONObject job) {
        if (!isActive(context) || job == null) return false;
        String action = job.optString("action", "status");
        if (!allowedAction(action)) return false;
        if (actionCount(context) >= MAX_ACTIONS) {
            stop(context, "action_limit");
            return false;
        }

        String expectedTarget = targetPackage(context);
        String suppliedTarget = job.optString("target_package", expectedTarget);
        if (!expectedTarget.equals(suppliedTarget)) return false;
        if ("launch".equals(action)
                && !expectedTarget.equals(job.optString("package", ""))) return false;

        if (isMutating(action)) {
            if (!UrsafeAccessibilityService.isReady()) return false;
            long now = System.currentTimeMillis();
            long last = BridgeCrypto.prefs(context).getLong(PREF_LAST_ACTION, 0L);
            if (now - last < MIN_ACTION_GAP_MS) return false;

            String foreground = ScreenFrameStore.foregroundPackage();
            if (("tap".equals(action) || "swipe".equals(action)
                    || "sequence".equals(action))
                    && !foreground.isEmpty() && !expectedTarget.equals(foreground)) {
                return false;
            }
            if ("back".equals(action) && isProtectedPackage(foreground)) return false;
        }
        return true;
    }

    public static synchronized void recordAction(Context context, JSONObject job) {
        int count = actionCount(context) + 1;
        BridgeCrypto.prefs(context).edit()
                .putInt(PREF_ACTIONS, count)
                .putLong(PREF_LAST_ACTION, System.currentTimeMillis())
                .putString("live_control_last_action",
                        job == null ? "" : job.optString("action", ""))
                .apply();
        if (count >= MAX_ACTIONS) stop(context, "action_limit");
    }

    public static JSONObject status(Context context) {
        JSONObject value = new JSONObject();
        try {
            value.put("active", isActive(context));
            value.put("session_id", sessionId(context));
            value.put("target_label", targetLabel(context));
            value.put("target_package", targetPackage(context));
            value.put("expires_at_ms", expiresAt(context));
            value.put("remaining_ms", remainingMs(context));
            value.put("action_count", actionCount(context));
            value.put("max_actions", MAX_ACTIONS);
        } catch (Exception ignored) {}
        return value;
    }

    private static boolean allowedAction(String action) {
        return "status".equals(action) || "screenshot".equals(action)
                || "tap".equals(action) || "swipe".equals(action)
                || "back".equals(action) || "launch".equals(action)
                || "sequence".equals(action);
    }

    private static boolean isMutating(String action) {
        return !"status".equals(action) && !"screenshot".equals(action);
    }

    private static boolean isProtectedPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return true;
        return packageName.equals("com.ursafe.app")
                || packageName.equals("com.android.systemui")
                || packageName.contains("permissioncontroller")
                || packageName.contains("settings")
                || packageName.contains("launcher")
                || packageName.contains("inputmethod");
    }

    private static void showNotification(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "Ursafe live control", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("არჩეულ თამაშზე დროით შეზღუდული live-control სესია");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Intent stop = new Intent(context, BridgeActionReceiver.class)
                .setAction(BridgeActionReceiver.ACTION_STOP_LIVE_CONTROL);
        PendingIntent stopIntent = PendingIntent.getBroadcast(context, 6901, stop,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_ursafe_logo)
                .setContentTitle("Ursafe Live Control აქტიურია")
                .setContentText(targetLabel(context) + " • დაშიფრული კონტროლი • Stop ყოველთვის ხელმისაწვდომია")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(0, "STOP", stopIntent).build())
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }
}
