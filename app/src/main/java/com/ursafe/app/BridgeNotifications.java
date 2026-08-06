package com.ursafe.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

public final class BridgeNotifications {
    public static final String CHANNEL_STATUS = "ursafe_bridge_status";
    public static final String CHANNEL_APPROVAL = "ursafe_bridge_approval";
    public static final int STATUS_ID = 6200;

    private BridgeNotifications() {}

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel status = new NotificationChannel(CHANNEL_STATUS,
                "Ursafe bridge", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("Termux bridge-ის ფონური სტატუსი");
        status.setShowBadge(false);
        NotificationChannel approval = new NotificationChannel(CHANNEL_APPROVAL,
                "Ursafe მოქმედებები", NotificationManager.IMPORTANCE_HIGH);
        approval.setDescription("ბრძანებისა და ეკრანის მოქმედების დადასტურება");
        approval.enableVibration(true);
        approval.enableLights(true);
        approval.setLightColor(Color.rgb(91, 69, 224));
        manager.createNotificationChannel(status);
        manager.createNotificationChannel(approval);
    }

    public static Notification serviceNotification(Context context) {
        ensureChannels(context);
        Intent stop = new Intent(context, BridgeActionReceiver.class)
                .setAction(BridgeActionReceiver.ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getBroadcast(context, 6201, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        Intent pairing = new Intent(context, LauncherActivity.class)
                .putExtra(LauncherActivity.EXTRA_SHOW_PAIRING, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pairingIntent = PendingIntent.getActivity(context, 6202, pairing,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        return new Notification.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_ursafe_logo)
                .setContentTitle("Ursafe bridge აქტიურია")
                .setContentText("დაშიფრულ დავალებებს ყოველ წამს ამოწმებს")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(0, "დაწყვილების კოდი", pairingIntent).build())
                .addAction(new Notification.Action.Builder(0, "შეჩერება", stopIntent).build())
                .build();
    }

    public static void showPending(Context context, String jobId, JSONObject job) {
        ensureChannels(context);
        String kind = job.optString("kind", "termux");
        String command = job.optString("command", "");
        String reason = job.optString("reason", "");
        String action = job.optString("action", "");
        JSONArray artifacts = job.optJSONArray("artifacts");
        Intent approve = new Intent(context, BridgeActionReceiver.class)
                .setAction(BridgeActionReceiver.ACTION_APPROVE).putExtra("job_id", jobId);
        PendingIntent approveIntent = PendingIntent.getBroadcast(context, jobId.hashCode(), approve,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        Intent reject = new Intent(context, BridgeActionReceiver.class)
                .setAction(BridgeActionReceiver.ACTION_REJECT).putExtra("job_id", jobId);
        PendingIntent rejectIntent = PendingIntent.getBroadcast(context,
                jobId.hashCode() ^ 0x5f3759df, reject,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        String title;
        String summary;
        StringBuilder details = new StringBuilder();
        if (!reason.isEmpty()) details.append(reason).append("\n\n");
        if ("device".equals(kind)) {
            title = "მოწყობილობის მოქმედება დასადასტურებელია";
            summary = deviceSummary(job);
            details.append(summary);
        } else {
            title = "Termux ბრძანება დასადასტურებელია";
            summary = command;
            details.append(command);
            if (artifacts != null && artifacts.length() > 0) {
                details.append("\n\nასატვირთი ფაილები:");
                for (int i = 0; i < artifacts.length(); i++) {
                    details.append("\n• ").append(artifacts.optString(i));
                }
            }
        }
        Notification notification = new Notification.Builder(context, CHANNEL_APPROVAL)
                .setSmallIcon(R.drawable.ic_ursafe_logo)
                .setContentTitle(title)
                .setContentText(shorten(summary, 120))
                .setStyle(new Notification.BigTextStyle().bigText(details.toString()))
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setPriority(Notification.PRIORITY_HIGH)
                .addAction(new Notification.Action.Builder(0, "უარყოფა", rejectIntent).build())
                .addAction(new Notification.Action.Builder(0, "დადასტურება", approveIntent).build())
                .build();
        manager(context).notify(jobId.hashCode(), notification);
    }

    private static String deviceSummary(JSONObject job) {
        String action = job.optString("action", "status");
        switch (action) {
            case "screenshot": return "ეკრანის მიმდინარე კადრის დაბრუნება";
            case "tap": return "Tap: x=" + job.opt("x") + ", y=" + job.opt("y");
            case "swipe": return "Swipe: (" + job.opt("from_x") + ", "
                    + job.opt("from_y") + ") → (" + job.opt("to_x") + ", "
                    + job.opt("to_y") + ")";
            case "back": return "Android Back მოქმედება";
            case "home": return "Android Home მოქმედება";
            case "launch": return "აპის გახსნა: " + job.optString("package", "");
            default: return "მოწყობილობის სტატუსის მიღება";
        }
    }

    public static void showMessage(Context context, String title, String message, int notificationId) {
        ensureChannels(context);
        Notification notification = new Notification.Builder(context, CHANNEL_APPROVAL)
                .setSmallIcon(R.drawable.ic_ursafe_logo)
                .setContentTitle(title)
                .setContentText(shorten(message, 140))
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build();
        manager(context).notify(notificationId, notification);
    }

    public static void cancelJob(Context context, String jobId) { manager(context).cancel(jobId.hashCode()); }
    private static NotificationManager manager(Context context) { return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE); }
    private static int immutableFlag() { return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0; }
    private static String shorten(String value, int max) {
        if (value == null) return "";
        String compact = value.replace('\n', ' ').trim();
        return compact.length() <= max ? compact : compact.substring(0, max) + "…";
    }
}
