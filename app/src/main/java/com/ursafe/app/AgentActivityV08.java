package com.ursafe.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class AgentActivityV08 extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            handler.postDelayed(this, 500L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(247, 248, 252));
        getWindow().setNavigationBarColor(Color.WHITE);
        buildUi();
        try { BridgeForegroundService.start(this); } catch (Exception ignored) {}
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

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(22), dp(18), dp(28));
        page.setBackgroundColor(Color.rgb(247, 248, 252));
        scroll.addView(page);
        setContentView(scroll);

        TextView title = text("Ursafe Agent", 28, true, Color.rgb(24, 26, 35));
        page.addView(title);
        TextView subtitle = text("Local vision + Termux bridge", 15, false, Color.rgb(92, 96, 112));
        subtitle.setPadding(0, dp(4), 0, dp(18));
        page.addView(subtitle);

        LinearLayout card = card();
        card.addView(text("დაბალი დაყოვნების საფუძველი", 21, true, Color.rgb(25, 27, 36)));
        TextView description = text(
                "ეკრანის კადრები ადგილობრივად მუშავდება. Android ყოველ capture სესიაზე სისტემურ თანხმობას ითხოვს. Accessibility ცალკე ირთვება და მხოლოდ ხილულ tap/swipe მოქმედებებს ასრულებს.",
                15, false, Color.rgb(83, 87, 103));
        description.setPadding(0, dp(10), 0, dp(14));
        card.addView(description);
        status = text("სტატუსი იტვირთება…", 14, false, Color.rgb(31, 34, 45));
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(rounded(Color.rgb(239, 240, 247), 18));
        card.addView(status);
        page.addView(card);

        LinearLayout actions = card();
        actions.addView(text("მართვა", 21, true, Color.rgb(25, 27, 36)));
        actions.addView(button("ეკრანის დაკვირვების ჩართვა", v ->
                startActivity(new Intent(this, ScreenConsentActivity.class))));
        actions.addView(button("Accessibility პარამეტრები", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        actions.addView(button("Bridge-ის გაშვება", v -> {
            try {
                BridgeForegroundService.start(this);
                toast("Bridge გაშვებულია.");
            } catch (Exception error) {
                toast("Bridge ვერ გაეშვა: " + error.getMessage());
            }
        }));
        actions.addView(button("დაწყვილების კოდი", v -> showPairingCode()));
        actions.addView(button("ეკრანის დაკვირვების შეჩერება", v -> {
            ScreenCaptureService.stop(this);
            toast("Screen observer შეჩერდა.");
        }));
        page.addView(actions);

        LinearLayout plan = card();
        plan.addView(text("Realtime გზა", 21, true, Color.rgb(25, 27, 36)));
        plan.addView(text(
                "v0.8: 12 FPS-მდე ადგილობრივი motion loop.\n" +
                "შემდეგი: თამაშის პროფილები, state detector და კონკრეტული მოქმედებების ადგილობრივი policy.\n" +
                "GitHub bridge გამოიყენება მიზნებისა და შედეგებისთვის — არა თითოეული კადრისთვის.",
                15, false, Color.rgb(83, 87, 103)));
        page.addView(plan);
    }

    private void updateStatus() {
        if (status == null) return;
        long age = ScreenFrameStore.timestampMs() == 0 ? -1
                : Math.max(0, System.currentTimeMillis() - ScreenFrameStore.timestampMs());
        String value =
                "Bridge: აქტიური\n" +
                "Screen observer: " + (ScreenCaptureService.isActive() ? "ON" : "OFF") + "\n" +
                "Accessibility: " + (UrsafeAccessibilityService.isReady() ? "ON" : "OFF") + "\n" +
                "Frames: " + ScreenFrameStore.frameCount() + "\n" +
                "Last frame age: " + (age < 0 ? "—" : age + " ms") + "\n" +
                "Motion: " + String.format(Locale.US, "%.3f", ScreenFrameStore.motion()) + "\n" +
                "Foreground: " + emptyDash(ScreenFrameStore.foregroundPackage());
        status.setText(value);
    }

    private void showPairingCode() {
        String code = BridgeCrypto.pairingCode(this);
        TextView value = text(code, 14, false, Color.rgb(35, 37, 45));
        value.setTextIsSelectable(true);
        value.setPadding(dp(18), dp(12), dp(18), dp(12));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("დაწყვილების კოდი")
                .setMessage("ეს კოდი პირადია. სხვაგან არ გააზიარო.")
                .setView(value)
                .setNegativeButton("დახურვა", null)
                .setPositiveButton("კოპირება", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Ursafe pairing code", code));
                    toast("კოდი დაკოპირდა.");
                }));
        dialog.show();
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(18), dp(16), dp(18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(16));
        view.setLayoutParams(params);
        view.setBackground(rounded(Color.WHITE, 24));
        view.setElevation(dp(2));
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.rgb(56, 48, 111));
        button.setBackground(rounded(Color.rgb(239, 237, 255), 16));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        params.setMargins(0, dp(10), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private TextView text(String value, int size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.18f);
        view.setGravity(Gravity.START);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private String emptyDash(String value) { return value == null || value.isEmpty() ? "—" : value; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
