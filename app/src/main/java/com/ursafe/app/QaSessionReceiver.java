package com.ursafe.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class QaSessionReceiver extends BroadcastReceiver {
    public static final String ACTION_STOP = "com.ursafe.app.qa.STOP";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_STOP.equals(intent.getAction())) return;
        NumberMatchAgent.stop(context);
        String result = QaSessionManager.stop(context, "notification_stop");
        BridgeNotifications.showMessage(context, "Ursafe QA დასრულდა", result, 6803);
    }
}
