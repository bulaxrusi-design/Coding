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
        String stdout = "";
        String stderr = "";
        int exitCode = -1;
        int errorCode = 0;

        if (result == null) {
            message = "Termux პასუხი ვერ დამუშავდა.";
        } else {
            stdout = value(result.getString("stdout"));
            stderr = value(result.getString("stderr"));
            String errorMessage = value(result.getString("errmsg"));
            exitCode = result.getInt("exitCode", -1);
            errorCode = result.getInt("err", 0);

            if (exitCode == 0 && errorCode == 0) {
                message = stdout.trim().isEmpty()
                        ? "Ursafe ↔ Termux კავშირი მუშაობს."
                        : stdout.trim();
            } else {
                StringBuilder builder = new StringBuilder();
                builder.append("Termux შეცდომა: exit=").append(exitCode);
                if (!errorMessage.isEmpty()) builder.append("\n").append(errorMessage);
                if (!stderr.isEmpty()) builder.append("\n").append(stderr.trim());
                if (!stdout.isEmpty()) builder.append("\nstdout:\n").append(stdout.trim());
                message = builder.toString();
            }
        }

        Intent update = new Intent(MainActivity.ACTION_TERMUX_RESULT);
        update.setPackage(getPackageName());
        update.putExtra("request_id", requestId);
        update.putExtra("request_kind", requestKind);
        update.putExtra("message", message);
        update.putExtra("stdout", stdout);
        update.putExtra("stderr", stderr);
        update.putExtra("exit_code", exitCode);
        update.putExtra("error_code", errorCode);
        sendBroadcast(update);
    }

    private static String value(String input) {
        return input == null ? "" : input;
    }
}
