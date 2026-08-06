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
    private TextView numberMatchStatus;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            handler.postDelayed(this, 350L);
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

        page.addView(text("Ursafe Agent", 28, true, Color.rgb(24, 26, 35)));
        TextView subtitle = text("Local vision + Number Match + Termux bridge",
                15, false, Color.rgb(92, 96, 112));
        subtitle.setPadding(0, dp(4), 0, dp(18));
        page.addView(subtitle);

        LinearLayout overview = card();
        overview.addView(text("დაბალი დაყოვნების რეჟიმი", 21, true,
                Color.rgb(25, 27, 36)));
        TextView description = text(
                "ეკრანის კადრები მოწყობილობაზევე მუშავდება. Number Match-ის OCR, "
                        + "წყვილის მოძებნა და tap-ები ინტერნეტის გარეშე სრულდება.",
                15, false, Color.rgb(83, 87, 103));
        description.setPadding(0, dp(10), 0, dp(14));
        overview.addView(description);
        status = text("სტატუსი იტვირთება…", 14, false, Color.rgb(31, 34, 45));
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(rounded(Color.rgb(239, 240, 247), 18));
        overview.addView(status);
        page.addView(overview);

        LinearLayout game = darkCard();
        game.addView(text("Number Match — ადგილობრივი მოთამაშე", 21, true,
                Color.WHITE));
        TextView gameInfo = text(
                "Start ერთხელ დააჭირე. Ursafe თვითონ გახსნის თამაშს, წაიკითხავს "
                        + "9-სვეტიან დაფას, იპოვის ერთნაირ ან ჯამში 10 წყვილებს და "
                        + "სვლებს ადგილობრივად შეასრულებს.",
                15, false, Color.rgb(215, 218, 232));
        gameInfo.setPadding(0, dp(10), 0, dp(12));
        game.addView(gameInfo);
        numberMatchStatus = text("Agent: OFF", 14, false, Color.rgb(225, 226, 241));
        numberMatchStatus.setPadding(dp(14), dp(12), dp(14), dp(12));
        numberMatchStatus.setBackground(rounded(Color.rgb(45, 48, 62), 16));
        game.addView(numberMatchStatus);
        game.addView(button("Number Match-ის დაწყება", v -> {
            String message = NumberMatchAgent.start(this);
            toast(message);
            updateStatus();
        }));
        game.addView(button("Number Match-ის შეჩერება", v -> {
            NumberMatchAgent.stop(this);
            toast("Number Match agent შეჩერდა.");
            updateStatus();
        }));
        page.addView(game);

        LinearLayout actions = card();
        actions.addView(text("სისტემური ნებართვები", 21, true,
                Color.rgb(25, 27, 36)));
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
            NumberMatchAgent.stop(this);
            ScreenCaptureService.stop(this);
            toast("Screen observer შეჩერდა.");
        }));
        page.addView(actions);

        LinearLayout limits = card();
        limits.addView(text("უსაფრთხო ლიმიტები", 21, true,
                Color.rgb(25, 27, 36)));
        limits.addView(text(
                "• მუშაობს მხოლოდ აპზე, რომლის სახელი Number Match-ია.\n"
                        + "• მაქსიმუმ 400 სვლა ერთ სესიაზე.\n"
                        + "• მაქსიმუმ 5 ავტომატური „+“.\n"
                        + "• შეტყობინებიდან ან ამ ეკრანიდან ნებისმიერ დროს ჩერდება.\n"
                        + "• ეკრანის დამუშავება ლოკალურია.",
                15, false, Color.rgb(83, 87, 103)));
        page.addView(limits);
    }

    private void updateStatus() {
        if (status == null) return;
        long age = ScreenFrameStore.timestampMs() == 0 ? -1
                : Math.max(0, System.currentTimeMillis() - ScreenFrameStore.timestampMs());
        String value =
                "Bridge: " + (BridgeForegroundService.isRunning() ? "ON" : "OFF") + "\n"
                + "Screen observer: " + (ScreenCaptureService.isActive() ? "ON" : "OFF") + "\n"
                + "Accessibility: " + (UrsafeAccessibilityService.isReady() ? "ON" : "OFF") + "\n"
                + "Frames: " + ScreenFrameStore.frameCount() + "\n"
                + "Last frame age: " + (age < 0 ? "—" : age + " ms") + "\n"
                + "Motion: " + String.format(Locale.US, "%.3f", ScreenFrameStore.motion()) + "\n"
                + "Foreground: " + emptyDash(ScreenFrameStore.foregroundPackage());
        status.setText(value);

        if (numberMatchStatus != null) {
            numberMatchStatus.setText(
                    "Agent: " + (NumberMatchAgent.isEnabled() ? "ON" : "OFF") + "\n"
                    + "State: " + NumberMatchAgent.status() + "\n"
                    + "Moves: " + NumberMatchAgent.moves() + "\n"
                    + "Added rows: " + NumberMatchAgent.additions() + "\n"
                    + "Recognized cells: " + NumberMatchAgent.recognizedCells() + "\n"
                    + "OCR confidence: "
                    + String.format(Locale.US, "%.1f%%",
                    NumberMatchAgent.confidence() * 100.0) + "\n"
                    + "Target: " + emptyDash(NumberMatchAgent.targetPackage()));
        }
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
                    clipboard.setPrimaryClip(
                            ClipData.newPlainText("Ursafe pairing code", code));
                    toast("კოდი დაკოპირდა.");
                }));
        dialog.show();
    }

    private LinearLayout card() {
        LinearLayout view = baseCard(Color.WHITE);
        view.setElevation(dp(2));
        return view;
    }

    private LinearLayout darkCard() {
        LinearLayout view = baseCard(Color.rgb(31, 33, 44));
        view.setElevation(dp(3));
        return view;
    }

    private LinearLayout baseCard(int color) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(18), dp(16), dp(18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(16));
        view.setLayoutParams(params);
        view.setBackground(rounded(color, 24));
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

    private String emptyDash(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
