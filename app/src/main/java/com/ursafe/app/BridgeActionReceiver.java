package com.ursafe.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

public final class BridgeActionReceiver extends BroadcastReceiver {
    public static final String ACTION_APPROVE = "com.ursafe.app.bridge.APPROVE";
    public static final String ACTION_REJECT = "com.ursafe.app.bridge.REJECT";
    public static final String ACTION_STOP = "com.ursafe.app.bridge.STOP";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) { context.stopService(new Intent(context, BridgeForegroundService.class)); return; }
        String jobId = safe(intent.getStringExtra("job_id"));
        if (jobId.isEmpty()) return;
        JSONObject job = BridgeForegroundService.pendingJob(context, jobId);
        if (job == null) {
            BridgeNotifications.showMessage(context, "Ursafe bridge", "ბრძანება აღარ არის აქტიური ან უკვე დამუშავებულია.", 6301);
            return;
        }
        BridgeNotifications.cancelJob(context, jobId);
        BridgeForegroundService.markHandled(context, jobId);
        if (ACTION_REJECT.equals(action)) {
            publishTerminalResult(context, jobId, "rejected", -1, "", "", "მომხმარებელმა ბრძანება უარყო.", job.optJSONArray("artifacts"));
            BridgeNotifications.showMessage(context, "ბრძანება გაუქმდა", "Termux-ში არაფერი შესრულებულა.", jobId.hashCode());
            return;
        }
        if (!ACTION_APPROVE.equals(action)) return;
        String command = job.optString("command", "");
        String blocked = BridgeCommandPolicy.rejectionReason(command);
        if (blocked != null) {
            publishTerminalResult(context, jobId, "blocked", -1, "", blocked, blocked, job.optJSONArray("artifacts"));
            BridgeNotifications.showMessage(context, "ბრძანება დაიბლოკა", blocked, jobId.hashCode());
            return;
        }
        try {
            TermuxRunner.runRemoteJob(context, jobId, job);
            BridgeNotifications.showMessage(context, "Termux მუშაობს", "დადასტურებული ბრძანება გაეშვა. დასრულების შემდეგ შედეგი აიტვირთება.", jobId.hashCode());
        } catch (Exception error) {
            String message = "ბრძანება ვერ გაეშვა: " + safe(error.getMessage());
            publishTerminalResult(context, jobId, "launch_failed", -1, "", message, message, job.optJSONArray("artifacts"));
            BridgeNotifications.showMessage(context, "Ursafe bridge შეცდომა", message, jobId.hashCode());
        }
    }

    public static void publishTerminalResult(Context context, String jobId, String status, int exitCode, String stdout, String stderr, String message, JSONArray artifacts) {
        try {
            JSONObject result = new JSONObject();
            result.put("v", 1);
            result.put("job_id", jobId);
            result.put("device_id", BridgeCrypto.getOrCreateDeviceId(context));
            result.put("status", status);
            result.put("exit_code", exitCode);
            result.put("stdout", truncate(stdout));
            result.put("stderr", truncate(stderr));
            result.put("message", truncate(message));
            result.put("created_at_ms", System.currentTimeMillis());
            result.put("artifacts", artifacts == null ? new JSONArray() : artifacts);
            JSONObject envelope = BridgeCrypto.encryptEnvelope(context, jobId, result);
            TermuxRunner.uploadEncryptedResult(context, jobId, envelope);
        } catch (Exception error) {
            BridgeNotifications.showMessage(context, "შედეგი ლოკალურად დარჩა", "GitHub-ზე დაშიფრული შედეგის ატვირთვა ვერ მოხერხდა. Termux-ში საჭიროა `pkg install gh python` და `gh auth login`. " + safe(error.getMessage()), 6302);
        }
    }

    private static String truncate(String value) {
        if (value == null) return "";
        int limit = 48000;
        return value.length() <= limit ? value : value.substring(0, limit) + "\n[…შემოკლებულია…]";
    }
    private static String safe(String value) { return value == null ? "" : value; }
}
