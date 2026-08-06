package com.ursafe.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BridgeForegroundService extends Service {
    private static final String PREF_HANDLED = "last_handled_job";
    private static final String PREF_NOTIFIED = "last_notified_job";
    private static final String PREF_PENDING = "pending_job";
    public static final long POLL_SECONDS = 1L;

    private static volatile boolean running;
    private ScheduledExecutorService executor;

    public static void start(Context context) {
        Intent intent = new Intent(context, BridgeForegroundService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static boolean isRunning() { return running; }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        startForeground(BridgeNotifications.STATUS_ID,
                BridgeNotifications.serviceNotification(this));
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::pollSafely, 0, POLL_SECONDS,
                TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (executor != null && !executor.isShutdown()) executor.execute(this::pollSafely);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (executor != null) executor.shutdownNow();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void pollSafely() {
        try {
            String deviceId = BridgeCrypto.getOrCreateDeviceId(this);
            String url = BridgeConfig.RAW_BASE + "ursafe-bridge/commands/"
                    + deviceId + ".json?ts=" + System.currentTimeMillis();
            String response = get(url);
            if (response == null || response.trim().isEmpty()) return;

            JSONObject envelope = new JSONObject(response);
            String jobId = envelope.optString("job_id", "");
            if (jobId.isEmpty()) return;

            String handled = BridgeCrypto.prefs(this).getString(PREF_HANDLED, "");
            String notified = BridgeCrypto.prefs(this).getString(PREF_NOTIFIED, "");
            if (jobId.equals(handled) || jobId.equals(notified)) return;

            JSONObject job = BridgeCrypto.decryptJob(this, envelope);
            String kind = job.optString("kind", "termux");
            if (!"device".equals(kind)) {
                String blocked = BridgeCommandPolicy.rejectionReason(
                        job.optString("command", ""));
                if (blocked != null) {
                    BridgeActionReceiver.publishTerminalResult(this, jobId,
                            "blocked", -1, "", blocked,
                            "Ursafe policy blocked the command.",
                            job.optJSONArray("artifacts"));
                    markHandled(jobId);
                    return;
                }
            }

            if ("device".equals(kind) && LiveControlSession.authorize(this, job)) {
                markHandled(jobId);
                DeviceActionRunner.run(this, jobId, job);
                return;
            }

            JSONObject pending = new JSONObject();
            pending.put("job_id", jobId);
            pending.put("job", job);
            BridgeCrypto.prefs(this).edit()
                    .putString(PREF_PENDING, pending.toString())
                    .putString(PREF_NOTIFIED, jobId)
                    .apply();
            BridgeNotifications.showPending(this, jobId, job);
        } catch (Exception error) {
            BridgeCrypto.prefs(this).edit()
                    .putString("last_poll_error", safe(error.getMessage()))
                    .putLong("last_poll_error_at", System.currentTimeMillis())
                    .apply();
        }
    }

    private void markHandled(String jobId) {
        BridgeCrypto.prefs(this).edit()
                .putString(PREF_HANDLED, jobId)
                .remove(PREF_PENDING)
                .remove(PREF_NOTIFIED)
                .apply();
    }

    public static void markHandled(Context context, String jobId) {
        BridgeCrypto.prefs(context).edit()
                .putString(PREF_HANDLED, jobId)
                .remove(PREF_PENDING)
                .remove(PREF_NOTIFIED)
                .apply();
    }

    public static JSONObject pendingJob(Context context, String expectedJobId) {
        try {
            String raw = BridgeCrypto.prefs(context).getString(PREF_PENDING, "");
            if (raw == null || raw.isEmpty()) return null;
            JSONObject pending = new JSONObject(raw);
            if (!expectedJobId.equals(pending.optString("job_id", ""))) return null;
            return pending.optJSONObject("job");
        } catch (Exception ignored) {
            return null;
        }
    }

    private String get(String value) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("User-Agent", "Ursafe-Agent/1.2");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setUseCaches(false);
        int status = connection.getResponseCode();
        if (status == 404) {
            connection.disconnect();
            return null;
        }
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String body = read(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("GitHub HTTP " + status + ": " + body);
        }
        return body;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 512000) {
                    throw new IllegalStateException("Bridge response is too large");
                }
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }

    private static String safe(String value) {
        return value == null ? "უცნობი შეცდომა" : value;
    }
}
