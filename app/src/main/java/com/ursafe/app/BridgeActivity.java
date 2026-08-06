package com.ursafe.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class BridgeActivity extends Activity {
    private static final String TERMUX = "com.termux";
    private static final String RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final int REQUEST_TERMUX = 7201;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView badge;
    private TextView status;
    private TextView jobs;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(247, 248, 252));
        getWindow().setNavigationBarColor(Color.WHITE);
        buildUi();
        startBridge();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 248, 252));
        setContentView(scroll);

        LinearLayout page = box();
        page.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header, match());

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_ursafe_logo);
        header.addView(logo, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout titles = box();
        titles.setPadding(dp(14), 0, 0, 0);
        titles.addView(label("Ursafe Bridge", 25, true, Color.rgb(22, 24, 32)), match());
        titles.addView(label("ChatGPT ↔ Termux", 13, false, Color.rgb(104, 108, 123)), match());
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        badge = label("STARTING", 11, true, Color.rgb(100, 76, 16));
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(11), dp(8), dp(11), dp(8));
        badge.setBackground(round(Color.rgb(255, 243, 205), 999));
        header.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        page.addView(gap(20));
        LinearLayout card = card(Color.WHITE);
        card.addView(label("Bridge-only რეჟიმი", 21, true, Color.rgb(28, 30, 39)), match());
        TextView info = label("ჩატი ამ აპიდან ამოღებულია. ამ ChatGPT ჩატს ვიყენებთ ინტერფეისად; Ursafe ფონურად იღებს დაშიფრულ დავალებებს, უშვებს Termux-ში და შედეგს უკან აბრუნებს.", 14, false, Color.rgb(89, 94, 109));
        info.setPadding(0, dp(10), 0, dp(15));
        info.setLineSpacing(dp(2), 1.08f);
        card.addView(info, match());
        status = label("იტვირთება…", 14, false, Color.rgb(34, 37, 48));
        status.setPadding(dp(14), dp(13), dp(14), dp(13));
        status.setBackground(round(Color.rgb(242, 244, 249), 14));
        card.addView(status, match());
        page.addView(card, match());

        page.addView(gap(16));
        LinearLayout terminal = card(Color.rgb(31, 34, 45));
        terminal.addView(label(">_  სამუშაო გარემო", 20, true, Color.WHITE), match());
        jobs = label("Queue იტვირთება…", 13, false, Color.rgb(211, 214, 226));
        jobs.setTypeface(Typeface.MONOSPACE);
        jobs.setTextIsSelectable(true);
        jobs.setPadding(0, dp(12), 0, 0);
        terminal.addView(jobs, match());
        page.addView(terminal, match());

        page.addView(gap(16));
        LinearLayout controls = card(Color.WHITE);
        controls.addView(label("მართვა", 19, true, Color.rgb(29, 31, 40)), match());
        controls.addView(gap(12));
        controls.addView(row(button("Bridge-ის გაშვება", true, v -> startBridge()), button("ახლავე შემოწმება", false, v -> startBridge())), match());
        controls.addView(gap(10));
        controls.addView(row(button("Termux ნებართვა", false, v -> requestTermux()), button("დაწყვილების კოდი", false, v -> showPairing())), match());
        controls.addView(gap(10));
        controls.addView(row(button("პარამეტრები", false, v -> openSettings()), button("Bridge-ის გაჩერება", false, v -> stopBridge())), match());
        page.addView(controls, match());

        TextView footer = label("Ursafe Bridge v0.7 • სწრაფი დაშიფრული transport", 12, false, Color.rgb(126, 130, 145));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(22), 0, 0);
        page.addView(footer, match());
    }

    private void startBridge() {
        try {
            BridgeForegroundService.start(this);
            toast("Bridge აქტიურია.");
        } catch (Exception e) {
            toast("Bridge ვერ გაეშვა: " + safe(e.getMessage()));
        }
        handler.postDelayed(this::updateStatus, 250L);
    }

    private void stopBridge() {
        stopService(new Intent(this, BridgeForegroundService.class));
        toast("Bridge გაჩერდა.");
        handler.postDelayed(this::updateStatus, 250L);
    }

    private void updateStatus() {
        if (status == null) return;
        boolean installed = isTermuxInstalled();
        boolean permission = checkSelfPermission(RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
        boolean running = BridgeForegroundService.isRunning();
        badge.setText(running ? "BRIDGE ON" : "BRIDGE OFF");
        badge.setTextColor(running ? Color.rgb(21, 122, 77) : Color.rgb(157, 45, 45));
        badge.setBackground(round(running ? Color.rgb(225, 247, 237) : Color.rgb(255, 231, 231), 999));
        status.setText("Bridge: " + (running ? "აქტიურია" : "გაჩერებულია") + "\nTermux: " + (installed ? "დაყენებულია" : "ვერ მოიძებნა") + "\nRUN_COMMAND: " + (permission ? "OK" : "ნებართვა აკლია") + "\nPolling: " + BridgeForegroundService.POLL_SECONDS + " წამი");
        String handled = safe(BridgeCrypto.prefs(this).getString("last_handled_job", ""));
        String notified = safe(BridgeCrypto.prefs(this).getString("last_notified_job", ""));
        boolean pending = !safe(BridgeCrypto.prefs(this).getString("pending_job", "")).isEmpty();
        jobs.setText("DEVICE  " + BridgeCrypto.getOrCreateDeviceId(this) + "\nLAST    " + (handled.isEmpty() ? "—" : handled) + "\nNOTIFY  " + (notified.isEmpty() ? "—" : notified) + "\nPENDING " + (pending ? "YES" : "NO"));
    }

    private void requestTermux() {
        if (!isTermuxInstalled()) { toast("Termux ვერ მოიძებნა."); return; }
        if (checkSelfPermission(RUN_COMMAND) == PackageManager.PERMISSION_GRANTED) { toast("ნებართვა უკვე მინიჭებულია."); return; }
        requestPermissions(new String[]{RUN_COMMAND}, REQUEST_TERMUX);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_TERMUX) {
            toast(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED ? "ნებართვა მინიჭებულია." : "ნებართვა არ მინიჭებულა.");
            updateStatus();
        }
    }

    private void showPairing() {
        String code = BridgeCrypto.pairingCode(this);
        TextView value = label(code, 14, false, Color.rgb(35, 37, 45));
        value.setTypeface(Typeface.MONOSPACE);
        value.setTextIsSelectable(true);
        value.setPadding(dp(18), dp(12), dp(18), dp(12));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("დაწყვილების კოდი").setMessage("ეს კოდი პირადია.").setView(value).setNegativeButton("დახურვა", null).setPositiveButton("კოპირება", null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Ursafe pairing", code));
            toast("კოდი დაკოპირდა.");
        }));
        dialog.show();
    }

    private void openSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private boolean isTermuxInstalled() {
        try { getPackageManager().getPackageInfo(TERMUX, 0); return true; }
        catch (PackageManager.NameNotFoundException ignored) { return false; }
    }

    private LinearLayout box() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout card(int color) { LinearLayout v = box(); v.setPadding(dp(18), dp(18), dp(18), dp(18)); v.setBackground(round(color, 22)); v.setElevation(dp(2)); return v; }
    private LinearLayout row(View a, View b) { LinearLayout v = new LinearLayout(this); v.addView(a, weight()); v.addView(spaceX()); v.addView(b, weight()); return v; }
    private TextView label(String value, int sp, boolean bold, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(color); v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL)); return v; }
    private Button button(String text, boolean primary, View.OnClickListener listener) { Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(13); b.setTypeface(Typeface.DEFAULT_BOLD); b.setStateListAnimator(null); b.setMinHeight(dp(50)); b.setTextColor(primary ? Color.WHITE : Color.rgb(57, 50, 103)); b.setBackground(round(primary ? Color.rgb(91, 69, 224) : Color.rgb(244, 242, 255), 14)); b.setOnClickListener(listener); return b; }
    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1f); }
    private View gap(int value) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(value))); return v; }
    private View spaceX() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1)); return v; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safe(String value) { return value == null ? "" : value; }
}
