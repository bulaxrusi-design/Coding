package com.ursafe.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public final class QaSessionManager {
    private static final Object LOCK = new Object();
    private static final String CHANNEL = "ursafe_qa_sessions";
    private static final int NOTIFICATION_ID = 6800;
    private static final long FRAME_SAMPLE_MS = 1000L;

    private static volatile boolean active;
    private static volatile String selectedLabel = "";
    private static volatile String selectedPackage = "";
    private static volatile String mode = "record_only";
    private static volatile String sessionId = "";
    private static volatile long startedAtMs;
    private static volatile long foregroundAtMs;
    private static volatile long firstFrameAtMs;
    private static volatile long lastFrameAtMs;
    private static volatile long frameSamples;
    private static volatile long accessibilityEvents;
    private static volatile long checkpoints;
    private static volatile String lastCheckpoint = "";
    private static volatile String lastExport = "";
    private static long lastSampleAtMs;
    private static BufferedWriter eventWriter;
    private static File eventFile;

    private QaSessionManager() {}

    public static String start(Context context, String label, String packageName, String requestedMode) {
        synchronized (LOCK) {
            if (packageName == null || !packageName.matches("[A-Za-z0-9._]+")) {
                return "ჯერ აირჩიე თამაში.";
            }
            if (!ScreenCaptureService.isActive()) return "ჯერ ჩართე ეკრანის დაკვირვება.";
            stopLocked(context, "replaced", false);
            selectedLabel = safe(label);
            selectedPackage = packageName;
            mode = safe(requestedMode).isEmpty() ? "record_only" : requestedMode;
            sessionId = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                    + "-" + UUID.randomUUID().toString().substring(0, 8);
            startedAtMs = System.currentTimeMillis();
            foregroundAtMs = 0L;
            firstFrameAtMs = 0L;
            lastFrameAtMs = 0L;
            frameSamples = 0L;
            accessibilityEvents = 0L;
            checkpoints = 0L;
            lastCheckpoint = "";
            lastSampleAtMs = 0L;
            lastExport = "";
            try {
                File dir = new File(context.getFilesDir(), "qa-sessions");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("QA საქაღალდე ვერ შეიქმნა");
                }
                eventFile = new File(dir, sessionId + ".jsonl");
                eventWriter = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(eventFile), StandardCharsets.UTF_8));
                active = true;
                writeEventLocked("session_start", json()
                        .put("game_label", selectedLabel)
                        .put("game_package", selectedPackage)
                        .put("mode", mode)
                        .put("authorized_test", true));
                showNotification(context);
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(selectedPackage);
                if (launch == null) {
                    throw new IllegalStateException("თამაშის გაშვების Intent ვერ მოიძებნა");
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(launch);
                writeEventLocked("launch_requested", json());
                return "QA სესია დაიწყო: " + selectedLabel;
            } catch (Exception error) {
                active = false;
                closeQuietly();
                return "QA სესია ვერ დაიწყო: " + safe(error.getMessage());
            }
        }
    }

    public static String stop(Context context, String reason) {
        synchronized (LOCK) {
            return stopLocked(context, safe(reason).isEmpty() ? "user_stop" : reason, true);
        }
    }

    private static String stopLocked(Context context, String reason, boolean export) {
        if (!active) {
            return lastExport.isEmpty()
                    ? "QA სესია აქტიური არ არის."
                    : "ბოლო ანგარიში: " + lastExport;
        }
        long stoppedAt = System.currentTimeMillis();
        try {
            writeEventLocked("session_stop", json().put("reason", reason));
            JSONObject summary = summaryJson(stoppedAt, reason);
            writeEventLocked("summary", summary);
            eventWriter.flush();
            File summaryFile = new File(eventFile.getParentFile(),
                    sessionId + "-summary.json");
            writeText(summaryFile, summary.toString(2));
            File csvFile = new File(eventFile.getParentFile(),
                    sessionId + "-summary.csv");
            writeText(csvFile, csvHeader() + "\n" + csvRow(summary, reason) + "\n");
            if (export) {
                String exported = exportToDownloads(context, summaryFile, "application/json");
                String exportedCsv = exportToDownloads(context, csvFile, "text/csv");
                lastExport = !exportedCsv.isEmpty() ? exportedCsv : exported;
            }
        } catch (Exception error) {
            lastExport = "export error: " + safe(error.getMessage());
        } finally {
            active = false;
            closeQuietly();
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            manager.cancel(NOTIFICATION_ID);
        }
        return lastExport.isEmpty()
                ? "QA სესია შეჩერდა."
                : "QA ანგარიში შენახულია: " + lastExport;
    }

    public static void onFrame(String foregroundPackage, long nowMs, double motion) {
        synchronized (LOCK) {
            if (!active) return;
            lastFrameAtMs = nowMs;
            if (selectedPackage.equals(foregroundPackage)) {
                if (foregroundAtMs == 0L) {
                    foregroundAtMs = nowMs;
                    writeEventLocked("target_foreground", json());
                }
                if (firstFrameAtMs == 0L) {
                    firstFrameAtMs = nowMs;
                    writeEventLocked("first_target_frame", json());
                }
            }
            if (nowMs - lastSampleAtMs < FRAME_SAMPLE_MS) return;
            lastSampleAtMs = nowMs;
            frameSamples++;
            writeEventLocked("frame_sample", json()
                    .put("foreground_package", safe(foregroundPackage))
                    .put("motion", motion)
                    .put("screen_frame_count", ScreenFrameStore.frameCount()));
        }
    }

    public static void onAccessibilityEvent(String packageName, int eventType, String text) {
        synchronized (LOCK) {
            if (!active) return;
            accessibilityEvents++;
            if (accessibilityEvents <= 20 || accessibilityEvents % 10 == 0) {
                writeEventLocked("accessibility_event", json()
                        .put("package", safe(packageName))
                        .put("event_type", eventType)
                        .put("text", truncate(text, 500)));
            }
        }
    }

    public static String checkpoint(String name) {
        synchronized (LOCK) {
            if (!active) return "QA სესია აქტიური არ არის.";
            checkpoints++;
            lastCheckpoint = safe(name).isEmpty()
                    ? "checkpoint-" + checkpoints : name;
            writeEventLocked("checkpoint", json()
                    .put("name", lastCheckpoint)
                    .put("elapsed_ms", System.currentTimeMillis() - startedAtMs));
            return "ნიშნული ჩაიწერა: " + lastCheckpoint;
        }
    }

    public static void recordAction(String action, String detail) {
        synchronized (LOCK) {
            if (!active) return;
            writeEventLocked("action", json()
                    .put("action", safe(action))
                    .put("detail", truncate(detail, 500)));
        }
    }

    public static boolean isActive() { return active; }
    public static String selectedLabel() { return selectedLabel; }
    public static String selectedPackage() { return selectedPackage; }
    public static String mode() { return mode; }
    public static String sessionId() { return sessionId; }
    public static long elapsedMs() {
        return active ? Math.max(0, System.currentTimeMillis() - startedAtMs) : 0;
    }
    public static long ttcToForegroundMs() {
        return foregroundAtMs == 0L ? -1 : foregroundAtMs - startedAtMs;
    }
    public static long ttcToFirstFrameMs() {
        return firstFrameAtMs == 0L ? -1 : firstFrameAtMs - startedAtMs;
    }
    public static long frameSamples() { return frameSamples; }
    public static long accessibilityEvents() { return accessibilityEvents; }
    public static long checkpoints() { return checkpoints; }
    public static String lastCheckpoint() { return lastCheckpoint; }
    public static String lastExport() { return lastExport; }

    private static JSONObject summaryJson(long stoppedAt, String reason) {
        return json()
                .put("session_id", sessionId)
                .put("game_label", selectedLabel)
                .put("game_package", selectedPackage)
                .put("mode", mode)
                .put("authorized_test", true)
                .put("started_at_ms", startedAtMs)
                .put("foreground_at_ms", foregroundAtMs)
                .put("first_frame_at_ms", firstFrameAtMs)
                .put("stopped_at_ms", stoppedAt)
                .put("duration_ms", stoppedAt - startedAtMs)
                .put("ttc_to_foreground_ms",
                        foregroundAtMs == 0L ? JSONObject.NULL : foregroundAtMs - startedAtMs)
                .put("ttc_to_first_frame_ms",
                        firstFrameAtMs == 0L ? JSONObject.NULL : firstFrameAtMs - startedAtMs)
                .put("frame_samples", frameSamples)
                .put("accessibility_events", accessibilityEvents)
                .put("checkpoints", checkpoints)
                .put("last_checkpoint", lastCheckpoint)
                .put("stop_reason", reason);
    }

    private static void writeEventLocked(String type, JSONObject payload) {
        if (eventWriter == null) return;
        try {
            payload.put("event", type);
            payload.put("session_id", sessionId);
            payload.put("timestamp_ms", System.currentTimeMillis());
            eventWriter.write(payload.toString());
            eventWriter.newLine();
            eventWriter.flush();
        } catch (Exception ignored) {}
    }

    private static SafeJson json() {
        return new SafeJson();
    }

    private static final class SafeJson extends JSONObject {
        @Override public SafeJson put(String name, Object value) {
            try {
                super.put(name, value);
            } catch (Exception ignored) {}
            return this;
        }
    }

    private static void showNotification(Context context) {
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Ursafe QA sessions", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("ხილული, ავტორიზებული თამაშის QA/TTC სესია");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Intent stopIntent = new Intent(context, QaSessionReceiver.class)
                .setAction(QaSessionReceiver.ACTION_STOP);
        PendingIntent stop = PendingIntent.getBroadcast(context, 6801, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        Intent openIntent = new Intent(context, AgentActivityV08.class);
        PendingIntent open = PendingIntent.getActivity(context, 6802, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_ursafe_logo)
                .setContentTitle("Ursafe QA: " + selectedLabel)
                .setContentText("ავტორიზებული TTC სესია მიმდინარეობს")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        0, "შეჩერება და ანგარიში", stop).build())
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    private static String exportToDownloads(Context context, File source, String mime)
            throws Exception {
        if (source == null || !source.exists()) return "";
        if (Build.VERSION.SDK_INT < 29) return source.getAbsolutePath();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, source.getName());
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Ursafe-QA");
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IllegalStateException("Downloads export ვერ შეიქმნა");
        }
        try (OutputStream output = resolver.openOutputStream(uri);
             java.io.FileInputStream input = new java.io.FileInputStream(source)) {
            if (output == null) {
                throw new IllegalStateException("Downloads stream unavailable");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
        return "Download/Ursafe-QA/" + source.getName();
    }

    private static void writeText(File file, String text) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(text);
        }
    }

    private static String csvHeader() {
        return "session_id,game_label,game_package,mode,started_at_ms,duration_ms,"
                + "ttc_to_foreground_ms,ttc_to_first_frame_ms,frame_samples,"
                + "accessibility_events,checkpoints,stop_reason";
    }

    private static String csvRow(JSONObject summary, String reason) {
        return csv(sessionId) + "," + csv(selectedLabel) + "," + csv(selectedPackage)
                + "," + csv(mode) + "," + startedAtMs + ","
                + summary.optLong("duration_ms") + ","
                + (foregroundAtMs == 0L ? "" : foregroundAtMs - startedAtMs) + ","
                + (firstFrameAtMs == 0L ? "" : firstFrameAtMs - startedAtMs) + ","
                + frameSamples + "," + accessibilityEvents + "," + checkpoints
                + "," + csv(reason);
    }

    private static String csv(String value) {
        return "\"" + safe(value).replace("\"", "\"\"") + "\"";
    }

    private static void closeQuietly() {
        try {
            if (eventWriter != null) eventWriter.close();
        } catch (Exception ignored) {}
        eventWriter = null;
        eventFile = null;
    }

    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private static String truncate(String value, int max) {
        String safe = safe(value);
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
