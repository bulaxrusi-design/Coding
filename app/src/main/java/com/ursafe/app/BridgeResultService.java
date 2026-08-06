package com.ursafe.app;

import android.app.IntentService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
public final class BridgeResultService extends IntentService {
    private static final int MAX_RESULT_CHARS = 48000;
    private static final int MAX_CHAT_CHARS = 12000;

    public BridgeResultService() {
        super("UrsafeTermuxResults");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;

        String requestKind = value(intent.getStringExtra("request_kind"));
        String command = value(intent.getStringExtra("command"));
        String jobId = value(intent.getStringExtra("job_id"));
        String jobJson = value(intent.getStringExtra("job_json"));
        int requestId = intent.getIntExtra("request_id", -1);
        Bundle result = intent.getBundleExtra("result");

        String stdout = "";
        String stderr = "";
        String errorMessage = "";
        int exitCode = -1;
        int errorCode = 0;
        String message;

        if (result == null) {
            message = "Termux პასუხი ვერ დამუშავდა.";
        } else {
            stdout = truncate(value(result.getString("stdout")));
            stderr = truncate(value(result.getString("stderr")));
            errorMessage = truncate(value(result.getString("errmsg")));
            exitCode = result.getInt("exitCode", -1);
            errorCode = result.getInt("err", 0);
            if (exitCode == 0 && errorCode == 0) {
                message = "Termux შესრულდა: exit=0";
            } else {
                StringBuilder builder = new StringBuilder();
                builder.append("Termux შეცდომა: exit=").append(exitCode);
                if (!errorMessage.isEmpty()) builder.append("\n").append(errorMessage);
                if (!stderr.isEmpty()) builder.append("\n").append(stderr);
                message = truncate(builder.toString());
            }
        }

        String chatResult = buildChatResult(command, stdout, stderr,
                errorMessage, exitCode, errorCode);
        SharedPreferences preferences = getSharedPreferences(
                UrsafeActivity.PREFS, MODE_PRIVATE);
        preferences.edit()
                .putString(UrsafeActivity.PREF_PENDING_RESULT, chatResult)
                .apply();

        Intent update = new Intent(BridgeConfig.ACTION_LOCAL_RESULT);
        update.setPackage(getPackageName());
        update.putExtra("request_id", requestId);
        update.putExtra("request_kind", requestKind);
        update.putExtra("command", command);
        update.putExtra("message", message);
        update.putExtra("stdout", stdout);
        update.putExtra("stderr", stderr);
        update.putExtra("exit_code", exitCode);
        update.putExtra("error_code", errorCode);
        update.putExtra("error_message", errorMessage);
        update.putExtra("chat_result", chatResult);
        sendBroadcast(update);

        if (!"bridge_remote".equals(requestKind) || jobId.isEmpty()) return;

        JSONArray artifacts = new JSONArray();
        try {
            if (!jobJson.isEmpty()) {
                JSONObject job = new JSONObject(jobJson);
                JSONArray configured = job.optJSONArray("artifacts");
                if (configured != null) artifacts = configured;
            }
        } catch (Exception ignored) {
            artifacts = new JSONArray();
        }

        String status = exitCode == 0 && errorCode == 0 ? "completed" : "failed";
        BridgeActionReceiver.publishTerminalResult(this, jobId, status, exitCode,
                stdout, stderr.isEmpty() ? errorMessage : stderr, message, artifacts);
        BridgeNotifications.showMessage(this,
                status.equals("completed") ? "Termux დავალება დასრულდა"
                        : "Termux დავალება შეცდომით დასრულდა",
                message, jobId.hashCode());
    }

    private static String buildChatResult(String command, String stdout,
                                          String stderr, String errorMessage,
                                          int exitCode, int errorCode) {
        StringBuilder out = new StringBuilder();
        out.append("URSAFE_TERMUX_RESULT\n");
        out.append("exit_code=").append(exitCode).append("\n");
        out.append("bridge_error_code=").append(errorCode).append("\n");
        if (!command.isEmpty()) out.append("command:\n").append(command).append("\n");
        if (!stdout.isEmpty()) out.append("stdout:\n").append(stdout).append("\n");
        if (!stderr.isEmpty()) out.append("stderr:\n").append(stderr).append("\n");
        if (!errorMessage.isEmpty()) {
            out.append("bridge_error:\n").append(errorMessage).append("\n");
        }
        if (stdout.isEmpty() && stderr.isEmpty() && errorMessage.isEmpty()) {
            out.append("No output was produced.\n");
        }
        String value = out.toString();
        return value.length() <= MAX_CHAT_CHARS
                ? value : value.substring(0, MAX_CHAT_CHARS) + "\n[…შედეგი შემოკლებულია…]";
    }

    private static String value(String input) {
        return input == null ? "" : input;
    }

    private static String truncate(String input) {
        return input.length() <= MAX_RESULT_CHARS
                ? input : input.substring(0, MAX_RESULT_CHARS) + "\n[…შემოკლებულია…]";
    }
}
