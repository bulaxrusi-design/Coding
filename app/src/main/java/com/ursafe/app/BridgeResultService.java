package com.ursafe.app;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;

@SuppressWarnings("deprecation")
public final class BridgeResultService extends IntentService {
    private static final int MAX_RESULT_CHARS = 12000;

    public BridgeResultService() {
        super("UrsafeTermuxResults");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;

        String requestKind = value(intent.getStringExtra("request_kind"));
        String command = value(intent.getStringExtra("command"));
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
                message = stdout.trim().isEmpty()
                        ? "Ursafe ↔ Termux კავშირი მუშაობს. exit=0"
                        : "Termux შესრულდა: exit=0";
            } else {
                StringBuilder builder = new StringBuilder();
                builder.append("Termux შეცდომა: exit=").append(exitCode);
                if (!errorMessage.isEmpty()) builder.append("\n").append(errorMessage);
                if (!stderr.isEmpty()) builder.append("\n").append(stderr);
                message = truncate(builder.toString());
            }
        }

        Intent update = new Intent(UrsafeActivity.ACTION_TERMUX_RESULT);
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
        sendBroadcast(update);
    }

    private static String value(String input) {
        return input == null ? "" : input;
    }

    private static String truncate(String input) {
        if (input.length() <= MAX_RESULT_CHARS) return input;
        return input.substring(0, MAX_RESULT_CHARS) + "\n[…შემოკლებულია…]";
    }
}
