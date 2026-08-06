package com.ursafe.app;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;

@SuppressWarnings("deprecation")
public final class BridgeUploadResultService extends IntentService {
    public BridgeUploadResultService() { super("UrsafeBridgeUploads"); }

    @Override protected void onHandleIntent(Intent intent) {
        if (intent == null) return;
        String jobId = value(intent.getStringExtra("job_id"));
        Bundle result = intent.getBundleExtra("result");
        int exitCode = result == null ? -1 : result.getInt("exitCode", -1);
        int errorCode = result == null ? -1 : result.getInt("err", -1);
        String stderr = result == null ? "" : value(result.getString("stderr"));
        String errorMessage = result == null ? "" : value(result.getString("errmsg"));
        if (exitCode == 0 && errorCode == 0) {
            BridgeNotifications.showMessage(this, "შედეგი აიტვირთა", "დაშიფრული Termux შედეგი მზადაა Ursafe bridge-ში.", jobId.hashCode() ^ 0x33aa55);
            return;
        }
        String details = "GitHub upload ვერ შესრულდა: exit=" + exitCode;
        if (!errorMessage.isEmpty()) details += "\n" + errorMessage;
        if (!stderr.isEmpty()) details += "\n" + stderr;
        BridgeNotifications.showMessage(this, "შედეგი ვერ აიტვირთა", details + "\n\nTermux-ში შეასრულე: pkg install gh python && gh auth login", jobId.hashCode() ^ 0x55aa33);
    }

    private static String value(String input) { return input == null ? "" : input; }
}
