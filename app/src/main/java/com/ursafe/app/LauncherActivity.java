package com.ursafe.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

public final class LauncherActivity extends Activity {
    public static final String EXTRA_SHOW_PAIRING = "show_pairing";
    private static final int REQUEST_NOTIFICATIONS = 8801;
    private boolean continuing;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(247, 248, 252));
        getWindow().setNavigationBarColor(Color.WHITE);
        boolean showPairing = getIntent().getBooleanExtra(EXTRA_SHOW_PAIRING, false);
        boolean firstRun = !BridgeCrypto.prefs(this).getBoolean("pairing_shown", false);
        if (showPairing || firstRun) showPairingDialog();
        else requestNotificationsThenContinue();
    }

    private void showPairingDialog() {
        String code = BridgeCrypto.pairingCode(this);
        TextView value = new TextView(this);
        value.setText(code);
        value.setTextSize(15);
        value.setTextIsSelectable(true);
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        value.setPadding(dp(20), dp(14), dp(20), dp(14));

        AlertDialog alert = new AlertDialog.Builder(this)
                .setTitle("Ursafe Bridge დაწყვილება")
                .setMessage("ეს კოდი პირადია. გამომიგზავნე მხოლოდ ამ ჩატში, რათა ბრძანებები და შედეგები ბოლომდე დაშიფრული იყოს.")
                .setView(value)
                .setCancelable(false)
                .setNegativeButton("გაგრძელება", (dialog, which) -> {
                    BridgeCrypto.prefs(this).edit().putBoolean("pairing_shown", true).apply();
                    requestNotificationsThenContinue();
                })
                .setPositiveButton("კოპირება", null)
                .create();

        alert.setOnShowListener(dialog -> alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Ursafe pairing code", code));
            Toast.makeText(this, "დაწყვილების კოდი დაკოპირდა.", Toast.LENGTH_SHORT).show();
        }));
        alert.show();
    }

    private void requestNotificationsThenContinue() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        continueToApp();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) continueToApp();
    }

    private void continueToApp() {
        if (continuing) return;
        continuing = true;
        try { BridgeForegroundService.start(this); }
        catch (Exception error) { Toast.makeText(this, "Bridge ვერ გაეშვა: " + safe(error.getMessage()), Toast.LENGTH_LONG).show(); }
        startActivity(new Intent(this, UrsafeActivityV07.class));
        finish();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safe(String value) { return value == null ? "უცნობი შეცდომა" : value; }
}
