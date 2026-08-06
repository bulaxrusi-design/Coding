package com.ursafe.app;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;

@SuppressWarnings("deprecation")
public final class CommandResultService extends IntentService {
    public CommandResultService() {
        super("UrsafeTermuxResults");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;

        String requestKind = value(intent.getStringExtra("request_kind"));
        int requestId = intent.getIntExtra("request_id", -1);
        Bundle result = intent.getBundleExtra("result");
        String message;

        if (result == null) {
            message = "Termux პასუხი ვერ დამუშავდა.";
        } else {
            String stdout = value(result.getString("stdout"));
            String stderr = value(result.getString("stderr"));
            String errorMessage = value(result.getString("errmsg"));
            int exitCode = result.getInt("exitCode", -1);
            int errorCode = result.getInt("err", 0);

            if (exitCode == 0 && errorCode == 0) {
                message = stdout.trim().isEmpty()
                        ? "Ursafe ↔ Termux კავშირი მუშაობს."
                        : stdout.trim();
            } else {
                StringBuilder builder = new StringBuilder();
                builder.append("Termux შეცდომა: exit=").append(exitCode);
                if (!errorMessage.isEmpty()) builder.append("\n").append(errorMessage);
                if (!stderr.isEmpty()) builder.append("\n").append(stderr);
                message = builder.toString();
            }
        }

        Intent update = new Intent(MainActivity.ACTION_TERMUX_RESULT);
        update.setPackage(getPackageName());
        update.putExtra("request_id", requestId);
        update.putExtra("request_kind", requestKind);
        update.putExtra("message", message);
        sendBroadcast(update);
    }

    private static String value(String input) {
        return input == null ? "" : input;
    }
}
