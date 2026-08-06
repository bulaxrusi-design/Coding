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

public final class AgentActivityV08 extends Activity {
    private static final String PREF_GAME_LABEL = "qa_game_label";
    private static final String PREF_GAME_PACKAGE = "qa_game_package";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView systemStatus;
    private TextView selectedGameStatus;
    private TextView qaStatus;
    private TextView profileStatus;
    private String selectedLabel = "";
    private String selectedPackage = "";

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

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(22), dp(18), dp(28));
        page.setBackgroundColor(Color.rgb(247, 248, 252));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("Ursafe QA Lab", 28, true, Color.rgb(24, 26, 35)));
        TextView subtitle = text("Authorized game testing + TTC analytics + Termux bridge",
                15, false, Color.rgb(92, 96, 112));
        subtitle.setPadding(0, dp(4), 0, dp(18));
        page.addView(subtitle);

        LinearLayout overview = card();
        overview.addView(text("სისტემური მდგომარეობა", 21, true, Color.rgb(25, 27, 36)));
        systemStatus = statusBox();
        overview.addView(systemStatus);
        page.addView(overview);

        LinearLayout gameCard = card();
        gameCard.addView(text("სატესტო თამაში", 21, true, Color.rgb(25, 27, 36)));
        TextView gameInfo = text(
                "აირჩიე ტელეფონზე დაყენებული თამაში. QA სესია ჩაიწერს გაშვების დროს, "
                        + "foreground/პირველი კადრის TTC-ს, ეკრანის მოძრაობას, Accessibility მოვლენებსა და ნიშნულებს.",
                15, false, Color.rgb(83, 87, 103));
        gameInfo.setPadding(0, dp(10), 0, dp(12));
        gameCard.addView(gameInfo);
        selectedGameStatus = statusBox();
        gameCard.addView(selectedGameStatus);
        gameCard.addView(button("თამაშის არჩევა", v -> showGamePicker()));
        gameCard.addView(button("QA/TTC სესიის დაწყება", v -> startQaSession()));
        gameCard.addView(button("TTC ნიშნულის ჩაწერა", v ->
                toast(QaSessionManager.checkpoint("checkpoint-" + (QaSessionManager.checkpoints() + 1)))));
        gameCard.addView(button("სესიის შეჩერება და ანგარიშის შენახვა", v -> {
            NumberMatchAgent.stop(this);
            toast(QaSessionManager.stop(this, "user_stop"));
            updateStatus();
        }));
        page.addView(gameCard);

        LinearLayout qaCard = darkCard();
        qaCard.addView(text("QA ანგარიში", 21, true, Color.WHITE));
        TextView qaInfo = text(
                "ანგარიში ინახება JSON/CSV ფორმატში Download/Ursafe-QA-ში. "
                        + "სესია ყოველთვის მონიშნულია როგორც authorized_test=true.",
                15, false, Color.rgb(215, 218, 232));
        qaInfo.setPadding(0, dp(10), 0, dp(12));
        qaCard.addView(qaInfo);
        qaStatus = darkStatusBox();
        qaCard.addView(qaStatus);
        page.addView(qaCard);

        LinearLayout profiles = card();
        profiles.addView(text("ავტონომიური თამაშის პროფილები", 21, true,
                Color.rgb(25, 27, 36)));
        TextView profileInfo = text(
                "ზოგადი framework ყველა თამაშს ხსნის და ზომავს. რეალური ავტომატური თამაში "
                        + "ემატება ცალკე პროფილად, რადგან სხვადასხვა თამაშს განსხვავებული წესები და ეკრანი აქვს.",
                15, false, Color.rgb(83, 87, 103));
        profileInfo.setPadding(0, dp(10), 0, dp(12));
        profiles.addView(profileInfo);
        profileStatus = statusBox();
        profiles.addView(profileStatus);
        profiles.addView(button("არჩეული პროფილის გაშვება", v -> startSelectedProfile()));
        profiles.addView(button("პროფილის შეჩერება", v -> {
            NumberMatchAgent.stop(this);
            toast("ავტონომიური პროფილი შეჩერდა.");
            updateStatus();
        }));
        page.addView(profiles);

        LinearLayout permissions = card();
        permissions.addView(text("სისტემური ნებართვები", 21, true,
                Color.rgb(25, 27, 36)));
        permissions.addView(button("ეკრანის დაკვირვების ჩართვა", v ->
                startActivity(new Intent(this, ScreenConsentActivity.class))));
        permissions.addView(button("Accessibility პარამეტრები", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        permissions.addView(button("Bridge-ის გაშვება", v -> {
            try {
                BridgeForegroundService.start(this);
                toast("Bridge გაშვებულია.");
            } catch (Exception error) {
                toast("Bridge ვერ გაეშვა: " + safe(error.getMessage()));
            }
        }));
        permissions.addView(button("დაწყვილების კოდი", v -> showPairingCode()));
        permissions.addView(button("ყველაფრის შეჩერება", v -> {
            NumberMatchAgent.stop(this);
            QaSessionManager.stop(this, "emergency_stop");
            ScreenCaptureService.stop(this);
            toast("Ursafe QA შეჩერდა.");
        }));
        page.addView(permissions);

        LinearLayout boundaries = card();
        boundaries.addView(text("Test/QA საზღვრები", 21, true, Color.rgb(25, 27, 36)));
        boundaries.addView(text(
                "• გამოიყენე კომპანიის მიერ ავტორიზებულ მოწყობილობასა და სატესტო ანგარიშებზე.\n"
                        + "• აპი არ მალავს ავტომატიზაციას და არ უვლის გვერდს anti-fraud/anti-cheat კონტროლს.\n"
                        + "• თითოეული თამაში მიიღებს ცალკე, ვერსირებულ პროფილსა და შედეგის ვალიდაციას.\n"
                        + "• Number Match პროფილი უკვე ჩაშენებულია; სხვა პროფილები ეტაპობრივად დაემატება.",
                15, false, Color.rgb(83, 87, 103)));
        page.addView(boundaries);
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
                .setTitle("აირჩიე სატესტო თამაში")
                .setItems(labels, (dialog, which) -> {
                    QaGameCatalog.Game game = games.get(which);
                    selectedLabel = game.label;
                    selectedPackage = game.packageName;
                    BridgeCrypto.prefs(this).edit()
                            .putString(PREF_GAME_LABEL, selectedLabel)
                            .putString(PREF_GAME_PACKAGE, selectedPackage)
                            .apply();
                    updateStatus();
                    toast("არჩეულია: " + selectedLabel);
                })
                .setNegativeButton("დახურვა", null)
                .show();
    }

    private void startQaSession() {
        String message = QaSessionManager.start(this, selectedLabel, selectedPackage, "record_only");
        toast(message);
        updateStatus();
    }

    private void startSelectedProfile() {
        if (selectedPackage.isEmpty()) {
            toast("ჯერ აირჩიე თამაში.");
            return;
        }
        String normalized = selectedLabel.toLowerCase(Locale.US);
        if (normalized.contains("number") && normalized.contains("match")) {
            if (!QaSessionManager.isActive()) {
                String qa = QaSessionManager.start(this, selectedLabel, selectedPackage,
                        "autonomous_profile:number_match");
                if (!QaSessionManager.isActive()) {
                    toast(qa);
                    return;
                }
            }
            toast(NumberMatchAgent.start(this));
            updateStatus();
            return;
        }
        toast("ამ თამაშის ავტონომიური პროფილი ჯერ არ არის დაყენებული. QA/TTC ჩაწერა უკვე მუშაობს.");
    }

    private void updateStatus() {
        long age = ScreenFrameStore.timestampMs() == 0 ? -1
                : Math.max(0, System.currentTimeMillis() - ScreenFrameStore.timestampMs());
        if (systemStatus != null) {
            systemStatus.setText(
                    "Bridge: " + (BridgeForegroundService.isRunning() ? "ON" : "OFF") + "\n"
                            + "Screen observer: " + (ScreenCaptureService.isActive() ? "ON" : "OFF") + "\n"
                            + "Accessibility: " + (UrsafeAccessibilityService.isReady() ? "ON" : "OFF") + "\n"
                            + "Frames: " + ScreenFrameStore.frameCount() + "\n"
                            + "Last frame: " + (age < 0 ? "—" : age + " ms") + "\n"
                            + "Foreground: " + emptyDash(ScreenFrameStore.foregroundPackage()));
        }
        if (selectedGameStatus != null) {
            selectedGameStatus.setText(
                    "Game: " + emptyDash(selectedLabel) + "\n"
                            + "Package: " + emptyDash(selectedPackage));
        }
        if (qaStatus != null) {
            qaStatus.setText(
                    "Session: " + (QaSessionManager.isActive() ? "RUNNING" : "OFF") + "\n"
                            + "ID: " + emptyDash(QaSessionManager.sessionId()) + "\n"
                            + "Mode: " + emptyDash(QaSessionManager.mode()) + "\n"
                            + "Elapsed: " + formatMs(QaSessionManager.elapsedMs()) + "\n"
                            + "TTC foreground: " + formatMaybe(QaSessionManager.ttcToForegroundMs()) + "\n"
                            + "TTC first frame: " + formatMaybe(QaSessionManager.ttcToFirstFrameMs()) + "\n"
                            + "Frame samples: " + QaSessionManager.frameSamples() + "\n"
                            + "Accessibility events: " + QaSessionManager.accessibilityEvents() + "\n"
                            + "Checkpoints: " + QaSessionManager.checkpoints() + "\n"
                            + "Last report: " + emptyDash(QaSessionManager.lastExport()));
        }
        if (profileStatus != null) {
            String installed = selectedLabel.toLowerCase(Locale.US).contains("number")
                    && selectedLabel.toLowerCase(Locale.US).contains("match")
                    ? "Number Match v1" : "record-only; profile pending";
            profileStatus.setText(
                    "Installed profile: " + installed + "\n"
                            + "Agent: " + (NumberMatchAgent.isEnabled() ? "ON" : "OFF") + "\n"
                            + "State: " + NumberMatchAgent.status() + "\n"
                            + "Moves: " + NumberMatchAgent.moves() + "\n"
                            + "OCR confidence: "
                            + String.format(Locale.US, "%.1f%%", NumberMatchAgent.confidence() * 100.0));
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
                    clipboard.setPrimaryClip(ClipData.newPlainText("Ursafe pairing code", code));
                    toast("კოდი დაკოპირდა.");
                }));
        dialog.show();
    }

    private TextView statusBox() {
        TextView view = text("იტვირთება…", 14, false, Color.rgb(31, 34, 45));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(rounded(Color.rgb(239, 240, 247), 18));
        return view;
    }

    private TextView darkStatusBox() {
        TextView view = text("იტვირთება…", 14, false, Color.rgb(225, 226, 241));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(rounded(Color.rgb(45, 48, 62), 16));
        return view;
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

    private String formatMaybe(long value) {
        return value < 0 ? "—" : formatMs(value);
    }

    private String formatMs(long value) {
        return String.format(Locale.US, "%.2f s", Math.max(0, value) / 1000.0);
    }

    private String emptyDash(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
