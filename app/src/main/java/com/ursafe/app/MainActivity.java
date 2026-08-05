package com.ursafe.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    public static final String ACTION_TERMUX_RESULT = "com.ursafe.app.TERMUX_RESULT";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final int REQUEST_TERMUX_PERMISSION = 501;
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger(1000);

    private TextView statusView;
    private boolean receiverRegistered;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra("message");
            setStatus(message == null ? "Termux-იდან ცარიელი პასუხი მივიღეთ." : message);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Ursafe");
        setContentView(createContent());
        refreshStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ACTION_TERMUX_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(resultReceiver, filter);
        }
        receiverRegistered = true;
        refreshStatus();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(resultReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private View createContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = text("Ursafe", 34, true);
        title.setTextColor(Color.rgb(20, 20, 24));
        root.addView(title, matchWrap());

        TextView subtitle = text("ChatGPT ანგარიში + Termux bridge", 18, false);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle, matchWrap());

        TextView note = text(
                "შესვლა ხდება მხოლოდ OpenAI-ის ოფიციალურ გვერდზე. Ursafe არ ხედავს და არ ინახავს შენს ელფოსტას, პაროლს ან Plus-ის მონაცემებს.",
                16,
                false);
        note.setLineSpacing(0f, 1.15f);
        root.addView(note, matchWrap());

        root.addView(space(18));
        root.addView(button("შესვლა ChatGPT ანგარიშით", v -> openUrl("https://chatgpt.com/auth/login")), matchWrap());
        root.addView(space(10));
        root.addView(button("ChatGPT-ის გახსნა", v -> openUrl("https://chatgpt.com/")), matchWrap());

        root.addView(space(26));
        TextView termuxTitle = text("Termux bridge", 24, true);
        root.addView(termuxTitle, matchWrap());

        TextView termuxNote = text(
                "ამ პირველ APK-ში Termux-ზე მხოლოდ ფიქსირებული, უსაფრთხო კავშირის ტესტი სრულდება. თავისუფალი ბრძანებების გაშვება ჯერ გამორთულია.",
                15,
                false);
        termuxNote.setPadding(0, dp(8), 0, dp(12));
        termuxNote.setLineSpacing(0f, 1.15f);
        root.addView(termuxNote, matchWrap());

        root.addView(button("Termux ნებართვის მოთხოვნა", v -> requestTermuxPermission()), matchWrap());
        root.addView(space(10));
        root.addView(button("Termux კავშირის ტესტი", v -> runTermuxTest()), matchWrap());
        root.addView(space(10));
        root.addView(button("Ursafe-ის Settings", v -> openAppSettings()), matchWrap());

        root.addView(space(18));
        statusView = text("სტატუსი იტვირთება…", 15, false);
        statusView.setPadding(dp(16), dp(16), dp(16), dp(16));
        statusView.setBackgroundColor(Color.rgb(238, 240, 245));
        root.addView(statusView, matchWrap());

        root.addView(space(20));
        TextView footer = text("Ursafe v0.3 MVP", 13, false);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(footer, matchWrap());
        return scroll;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            setStatus("ბრაუზერის გახსნა ვერ მოხერხდა: " + error.getMessage());
        }
    }

    private void requestTermuxPermission() {
        if (!isTermuxInstalled()) {
            setStatus("Termux ვერ მოიძებნა. დააყენე ოფიციალური Termux და შემდეგ სცადე თავიდან.");
            return;
        }
        if (checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            setStatus("Termux RUN_COMMAND ნებართვა უკვე მინიჭებულია.");
            return;
        }
        requestPermissions(new String[]{TERMUX_PERMISSION}, REQUEST_TERMUX_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_TERMUX_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            setStatus(granted
                    ? "Termux RUN_COMMAND ნებართვა მინიჭებულია."
                    : "ნებართვა არ მინიჭებულა. შეგიძლია Ursafe-ის Settings-იდან ჩართო.");
        }
    }

    private void runTermuxTest() {
        if (!isTermuxInstalled()) {
            setStatus("Termux ვერ მოიძებნა.");
            return;
        }
        if (checkSelfPermission(TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            setStatus("ჯერ მიანიჭე Termux RUN_COMMAND ნებართვა.");
            requestTermuxPermission();
            return;
        }

        int requestId = REQUEST_IDS.incrementAndGet();
        Intent resultIntent = new Intent(this, CommandResultService.class)
                .putExtra("request_id", requestId);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getService(this, requestId, resultIntent, flags);

        Intent command = new Intent();
        command.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
        command.setAction("com.termux.RUN_COMMAND");
        command.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        command.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{
                "-lc", "printf 'Ursafe ↔ Termux OK\\n'"
        });
        command.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        command.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Ursafe connection test");
        command.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION",
                "Runs a fixed printf command. It does not read or modify user data.");
        command.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent);

        try {
            startService(command);
            setStatus("Termux კავშირის ტესტი გაიგზავნა…");
        } catch (SecurityException error) {
            setStatus("Termux ნებართვის შეცდომა: " + error.getMessage());
        } catch (Exception error) {
            setStatus("Termux-თან დაკავშირება ვერ მოხერხდა: " + error.getMessage());
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private void refreshStatus() {
        if (statusView == null) return;
        String installed = isTermuxInstalled() ? "კი" : "არა";
        String permission = checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED
                ? "მინიჭებულია" : "არ არის მინიჭებული";
        setStatus("Termux დაყენებულია: " + installed + "\nRUN_COMMAND: " + permission);
    }

    private void setStatus(String value) {
        if (statusView != null) statusView.setText(value);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(45, 45, 52));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private View space(int dp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
