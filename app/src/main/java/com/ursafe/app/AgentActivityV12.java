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

import java.util.List;
import java.util.Locale;

public final class AgentActivityV12 extends Activity {
    private static final String PREF_GAME_LABEL = "qa_game_label";
    private static final String PREF_GAME_PACKAGE = "qa_game_package";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView systemStatus;
    private TextView gameStatus;
    private TextView liveStatus;
    private TextView localStatus;
    private String selectedLabel = "";
    private String selectedPackage = "";

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            handler.postDelayed(this, 300L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(247, 248, 252));
        getWindow().setNavigationBarColor(Color.WHITE);
        selectedLabel = BridgeCrypto.prefs(this).getString(PREF_GAME_LABEL, "");
        selectedPackage = BridgeCrypto.prefs(this).getString(PREF_GAME_PACKAGE, "");
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

    @Override public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(22), dp(18), dp(30));
        page.setBackgroundColor(Color.rgb(247, 248, 252));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("Ursafe Live QA", 29, true, Color.rgb(24, 26, 35)));
        TextView subtitle = text("დაშიფრული ChatGPT control + სწრაფი local execution + TTC logs",
                15, false, Color.rgb(92, 96, 112));
        subtitle.setPadding(0, dp(4), 0, dp(18));
        page.addView(subtitle);

        LinearLayout system = card(false);
        system.addView(text("სისტემა", 21, true, Color.rgb(25, 27, 36)));
        systemStatus = statusBox(false);
        system.addView(systemStatus);
        page.addView(system);

        LinearLayout game = card(false);
        game.addView(text("არჩეული თამაში", 21, true, Color.rgb(25, 27, 36)));
        game.addView(text("Live სესია მხოლოდ ამ package-ზე იმოქმედებს. სხვა აპები, Settings, კლავიატურა და System UI დაბლოკილია.",
                14, false, Color.rgb(83, 87, 103)));
        gameStatus = statusBox(false);
        game.addView(gameStatus);
        game.addView(button("თამაშის არჩევა", v -> showGamePicker(), false));
        page.addView(game);

        LinearLayout live = card(true);
        live.addView(text("ChatGPT Live Control", 22, true, Color.WHITE));
        TextView liveInfo = text(
                "ერთხელ ჩართავ სესიას. შემდეგ დაშიფრული screenshot/tap/swipe/back ბრძანებები "
                        + "თითო მოძრაობაზე ახალ ნებართვას აღარ ითხოვს. ყოველი მოქმედება აბრუნებს ახალ კადრს.",
                14, false, Color.rgb(218, 221, 235));
        liveInfo.setPadding(0, dp(8), 0, dp(8));
        live.addView(liveInfo);
        liveStatus = statusBox(true);
        live.addView(liveStatus);
        live.addView(button("Live Control-ის დაწყება — 45 წუთი", v -> startLive(), true));
        live.addView(button("Live Control-ის შეჩერება", v -> {
            LiveControlSession.stop(this, "user_stop");
            toast("Live Control შეჩერდა.");
            updateStatus();
        }, true));
        page.addView(live);

        LinearLayout local = card(false);
        local.addView(text("სწრაფი ადგილობრივი პროფილი", 21, true, Color.rgb(25, 27, 36)));
        local.addView(text(
                "განმეორებადი სწრაფი მოძრაობები ტელეფონზე სრულდება. Number Match v2-ში "
                        + "სვლა მხოლოდ ეკრანის რეალური ცვლილების შემდეგ ითვლება; რეკლამის Close/Skip ადგილობრივად მუშავდება.",
                14, false, Color.rgb(83, 87, 103)));
        localStatus = statusBox(false);
        local.addView(localStatus);
        local.addView(button("Number Match local პროფილის დაწყება", v -> startLocalProfile(), false));
        local.addView(button("Local პროფილის შეჩერება", v -> {
            NumberMatchAgent.stop(this);
            toast("Local პროფილი შეჩერდა.");
        }, false));
        page.addView(local);

        LinearLayout permissions = card(false);
        permissions.addView(text("ნებართვები და კავშირი", 21, true, Color.rgb(25, 27, 36)));
        permissions.addView(button("ეკრანის დაკვირვების ჩართვა", v ->
                startActivity(new Intent(this, ScreenConsentActivity.class)), false));
        permissions.addView(button("Accessibility პარამეტრები", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)), false));
        permissions.addView(button("Bridge-ის გაშვება", v -> {
            try {
                BridgeForegroundService.start(this);
                toast("Bridge აქტიურია.");
            } catch (Exception error) {
                toast("Bridge ვერ გაეშვა: " + safe(error.getMessage()));
            }
        }, false));
        permissions.addView(button("დაწყვილების კოდი", v -> showPairing(), false));
        permissions.addView(button("ყველაფრის დაუყოვნებლივ შეჩერება", v -> {
            LiveControlSession.stop(this, "emergency_stop");
            NumberMatchAgent.stop(this);
            QaSessionManager.stop(this, "emergency_stop");
            ScreenCaptureService.stop(this);
            toast("Ursafe მთლიანად შეჩერდა.");
        }, false));
        page.addView(permissions);

        TextView footer = text("Ursafe v1.2 • encrypted bounded live-control • no OpenAI API key in the app",
                12, false, Color.rgb(126, 130, 145));
        footer.setGravity(Gravity.CENTER);
        page.addView(footer);
    }

    private void showGamePicker() {
        List<QaGameCatalog.Game> games = QaGameCatalog.list(this);
        if (games.isEmpty()) {
            toast("გასაშვები თამაშები ვერ მოიძებნა.");
            return;
        }
        String[] labels = new String[games.size()];
        for (int i = 0; i < games.size(); i++) labels[i] = games.get(i).toString();
        new AlertDialog.Builder(this)
                .setTitle("აირჩიე თამაში")
                .setItems(labels, (dialog, which) -> {
                    QaGameCatalog.Game game = games.get(which);
                    selectedLabel = game.label;
                    selectedPackage = game.packageName;
                    BridgeCrypto.prefs(this).edit()
                            .putString(PREF_GAME_LABEL, selectedLabel)
                            .putString(PREF_GAME_PACKAGE, selectedPackage)
                            .apply();
                    updateStatus();
                })
                .setNegativeButton("დახურვა", null)
                .show();
    }

    private void startLive() {
        if (selectedPackage.isEmpty()) {
            toast("ჯერ აირჩიე თამაში.");
            return;
        }
        if (!QaSessionManager.isActive()) {
            QaSessionManager.start(this, selectedLabel, selectedPackage,
                    "chatgpt_live_control");
        }
        toast(LiveControlSession.start(this, selectedLabel, selectedPackage, 45));
        updateStatus();
    }

    private void startLocalProfile() {
        if (selectedPackage.isEmpty()) {
            toast("ჯერ აირჩიე Number Match.");
            return;
        }
        String normalized = selectedLabel.toLowerCase(Locale.ROOT);
        if (!normalized.contains("number") || !normalized.contains("match")) {
            toast("არჩეული თამაში Number Match არ არის.");
            return;
        }
        if (!QaSessionManager.isActive()) {
            QaSessionManager.start(this, selectedLabel, selectedPackage,
                    "local_profile:number_match_v2");
        }
        toast(NumberMatchAgent.start(this));
        updateStatus();
    }

    private void updateStatus() {
        long frameAge = ScreenFrameStore.timestampMs() == 0L ? -1L
                : Math.max(0L, System.currentTimeMillis() - ScreenFrameStore.timestampMs());
        if (systemStatus != null) {
            systemStatus.setText(
                    "Bridge: " + onOff(BridgeForegroundService.isRunning()) + "\n"
                            + "Screen observer: " + onOff(ScreenCaptureService.isActive()) + "\n"
                            + "Accessibility: " + onOff(UrsafeAccessibilityService.isReady()) + "\n"
                            + "Frames: " + ScreenFrameStore.frameCount() + "\n"
                            + "Last frame: " + (frameAge < 0 ? "—" : frameAge + " ms") + "\n"
                            + "Foreground: " + dash(ScreenFrameStore.foregroundPackage()));
        }
        if (gameStatus != null) {
            gameStatus.setText("Game: " + dash(selectedLabel) + "\nPackage: " + dash(selectedPackage));
        }
        if (liveStatus != null) {
            boolean live = LiveControlSession.isActive(this);
            liveStatus.setText(
                    "Session: " + (live ? "LIVE" : "OFF") + "\n"
                            + "Target: " + dash(LiveControlSession.targetLabel(this)) + "\n"
                            + "Remaining: " + formatMs(LiveControlSession.remainingMs(this)) + "\n"
                            + "Actions: " + LiveControlSession.actionCount(this) + "\n"
                            + "Relay polling: " + BridgeForegroundService.POLL_SECONDS + " s");
        }
        if (localStatus != null) {
            localStatus.setText(
                    "Agent: " + onOff(NumberMatchAgent.isEnabled()) + "\n"
                            + "State: " + NumberMatchAgent.status() + "\n"
                            + "Confirmed moves: " + NumberMatchAgent.moves() + "\n"
                            + "Additions: " + NumberMatchAgent.additions() + "\n"
                            + "OCR: " + String.format(Locale.US, "%.1f%%",
                            NumberMatchAgent.confidence() * 100.0));
        }
    }

    private void showPairing() {
        String code = BridgeCrypto.pairingCode(this);
        TextView value = text(code, 14, false, Color.rgb(35, 37, 45));
        value.setTextIsSelectable(true);
        value.setPadding(dp(18), dp(12), dp(18), dp(12));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("დაწყვილების კოდი")
                .setMessage("ეს კოდი პირადია. გამოიყენე მხოლოდ ამ ჩატთან დასაწყვილებლად.")
                .setView(value)
                .setNegativeButton("დახურვა", null)
                .setPositiveButton("კოპირება", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Ursafe pairing", code));
                    toast("კოდი დაკოპირდა.");
                }));
        dialog.show();
    }

    private LinearLayout card(boolean dark) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(18), dp(16), dp(18));
        view.setBackground(rounded(dark ? Color.rgb(31, 33, 44) : Color.WHITE, 24));
        view.setElevation(dp(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(16));
        view.setLayoutParams(params);
        return view;
    }

    private TextView statusBox(boolean dark) {
        TextView view = text("იტვირთება…", 14, false,
                dark ? Color.rgb(225, 226, 241) : Color.rgb(31, 34, 45));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(rounded(dark ? Color.rgb(45, 48, 62)
                : Color.rgb(239, 240, 247), 16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(10), 0, 0);
        view.setLayoutParams(params);
        return view;
    }

    private Button button(String label, View.OnClickListener listener, boolean dark) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(dark ? Color.WHITE : Color.rgb(56, 48, 111));
        button.setBackground(rounded(dark ? Color.rgb(91, 69, 224)
                : Color.rgb(239, 237, 255), 16));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
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
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private String onOff(boolean value) { return value ? "ON" : "OFF"; }
    private String dash(String value) { return value == null || value.isEmpty() ? "—" : value; }
    private String formatMs(long value) {
        if (value <= 0L) return "—";
        long seconds = value / 1000L;
        return (seconds / 60L) + "m " + (seconds % 60L) + "s";
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safe(String value) { return value == null ? "" : value; }
}
