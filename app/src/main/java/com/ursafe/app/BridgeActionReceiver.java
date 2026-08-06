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
    public static final String ACTION_STOP_NUMBER_MATCH =
            "com.ursafe.app.numbermatch.STOP";
    public static final String ACTION_STOP_LIVE_CONTROL =
            "com.ursafe.app.livecontrol.STOP";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_STOP_LIVE_CONTROL.equals(action)) {
            LiveControlSession.stop(context, "notification_stop");
            BridgeNotifications.showMessage(context, "Ursafe Live Control",
                    "Live-control სესია შეჩერდა.", 6902);
            return;
        }
        if (ACTION_STOP_NUMBER_MATCH.equals(action)) {
            NumberMatchAgent.stop(context);
            BridgeNotifications.showMessage(context, "Number Match agent",
                    "ადგილობრივი მოთამაშე შეჩერდა.", 6702);
            return;
        }
        if (ACTION_STOP.equals(action)) {
            context.stopService(new Intent(context, BridgeForegroundService.class));
            return;
        }

        String jobId = safe(intent.getStringExtra("job_id"));
        if (jobId.isEmpty()) return;
        JSONObject job = BridgeForegroundService.pendingJob(context, jobId);
        if (job == null) {
            BridgeNotifications.showMessage(context, "Ursafe bridge",
                    "დავალება აღარ არის აქტიური ან უკვე დამუშავებულია.", 6301);
            return;
        }
        BridgeNotifications.cancelJob(context, jobId);
        BridgeForegroundService.markHandled(context, jobId);
        if (ACTION_REJECT.equals(action)) {
            publishTerminalResult(context, jobId, "rejected", -1, "", "",
                    "მომხმარებელმა დავალება უარყო.", job.optJSONArray("artifacts"));
            BridgeNotifications.showMessage(context, "დავალება გაუქმდა",
                    "არაფერი შესრულებულა.", jobId.hashCode());
            return;
        }
        if (!ACTION_APPROVE.equals(action)) return;

        String kind = job.optString("kind", "termux");
        if ("device".equals(kind)) {
            DeviceActionRunner.run(context, jobId, job);
            BridgeNotifications.showMessage(context, "Ursafe device action",
                    "დადასტურებული მოქმედება დამუშავდა.", jobId.hashCode());
            return;
        }

        String command = job.optString("command", "");
        String blocked = BridgeCommandPolicy.rejectionReason(command);
        if (blocked != null) {
            publishTerminalResult(context, jobId, "blocked", -1, "", blocked,
                    blocked, job.optJSONArray("artifacts"));
            BridgeNotifications.showMessage(context, "ბრძანება დაიბლოკა",
                    blocked, jobId.hashCode());
            return;
        }
        try {
            TermuxRunner.runRemoteJob(context, jobId, job);
            BridgeNotifications.showMessage(context, "Termux მუშაობს",
                    "დადასტურებული ბრძანება გაეშვა. დასრულების შემდეგ შედეგი აიტვირთება.",
                    jobId.hashCode());
        } catch (Exception error) {
            String message = "ბრძანება ვერ გაეშვა: " + safe(error.getMessage());
            publishTerminalResult(context, jobId, "launch_failed", -1, "", message,
                    message, job.optJSONArray("artifacts"));
            BridgeNotifications.showMessage(context, "Ursafe bridge შეცდომა",
                    message, jobId.hashCode());
        }
    }

    public static void publishJsonResult(Context context, String jobId,
                                         JSONObject result) {
        try {
            if (!result.has("v")) result.put("v", 1);
            if (!result.has("job_id")) result.put("job_id", jobId);
            if (!result.has("device_id")) {
                result.put("device_id", BridgeCrypto.getOrCreateDeviceId(context));
            }
            if (!result.has("created_at_ms")) {
                result.put("created_at_ms", System.currentTimeMillis());
            }
            JSONObject envelope = BridgeCrypto.encryptEnvelope(context, jobId, result);
            TermuxRunner.uploadEncryptedResult(context, jobId, envelope);
        } catch (Exception error) {
            BridgeNotifications.showMessage(context, "შედეგი ლოკალურად დარჩა",
                    "დაშიფრული შედეგის ატვირთვა ვერ მოხერხდა: "
                            + safe(error.getMessage()), 6302);
        }
    }

    public static void publishTerminalResult(Context context, String jobId,
                                             String status, int exitCode,
                                             String stdout, String stderr,
                                             String message, JSONArray artifacts) {
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
            result.put("artifacts",
                    artifacts == null ? new JSONArray() : artifacts);
            publishJsonResult(context, jobId, result);
        } catch (Exception error) {
            BridgeNotifications.showMessage(context, "შედეგი ლოკალურად დარჩა",
                    "GitHub-ზე დაშიფრული შედეგის ატვირთვა ვერ მოხერხდა. "
                            + safe(error.getMessage()), 6302);
        }
    }

    private static String truncate(String value) {
        if (value == null) return "";
        int limit = 48000;
        return value.length() <= limit ? value
                : value.substring(0, limit) + "\n[…შემოკლებულია…]";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
