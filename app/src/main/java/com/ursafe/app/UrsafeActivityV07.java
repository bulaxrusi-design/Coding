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
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public final class UrsafeActivityV07 extends Activity {
    private static final String CHAT_URL = "https://chatgpt.com/";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final int REQUEST_FILE_CHOOSER = 7101;
    private static final int REQUEST_TERMUX_PERMISSION = 7102;

    private WebView webView;
    private ProgressBar progress;
    private TextView statusText;
    private TextView bridgeBadge;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(250, 250, 252));
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        buildUi();
        try {
            BridgeForegroundService.start(this);
        } catch (Exception ignored) {
            // Launcher normally starts the service. This is a best-effort fallback.
        }
        webView.loadUrl(CHAT_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // OAuth/history pages are not replayed. Back backgrounds Ursafe immediately.
        moveTaskToBack(true);
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        setContentView(page);

        page.addView(buildToolbar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(3);
        page.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        configureWebView();
        page.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        statusText = text("Bridge იტვირთება…", 11, false, Color.rgb(82, 86, 100));
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(dp(12), 0, dp(12), 0);
        statusText.setBackgroundColor(Color.rgb(246, 246, 250));
        page.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        refreshStatus();
    }

    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(5), dp(8), dp(5));
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(3));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_ursafe_logo);
        logo.setContentDescription("Ursafe");
        bar.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, 0, 0);
        titles.addView(text("Ursafe Chat", 16, true, Color.rgb(25, 27, 36)));
        titles.addView(text("Plus • files • Termux", 11, false, Color.rgb(112, 116, 130)));
        bar.addView(titles, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        bridgeBadge = text("BRIDGE", 10, true, Color.rgb(21, 122, 77));
        bridgeBadge.setGravity(Gravity.CENTER);
        bridgeBadge.setPadding(dp(10), dp(7), dp(10), dp(7));
        bridgeBadge.setBackground(rounded(Color.rgb(225, 247, 237), 999));
        bridgeBadge.setOnClickListener(v -> showMenu());
        bar.addView(bridgeBadge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView reload = toolbarButton("↻");
        reload.setContentDescription("განახლება");
        reload.setOnClickListener(v -> webView.reload());
        bar.addView(reload, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView menu = toolbarButton("⋮");
        menu.setContentDescription("მენიუ");
        menu.setOnClickListener(v -> showMenu());
        bar.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return bar;
    }

    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(buildFileIntent(params), REQUEST_FILE_CHOOSER);
                    return true;
                } catch (Exception error) {
                    fileCallback = null;
                    toast("ფაილების ამრჩევი ვერ გაიხსნა.");
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = safe(uri.getScheme()).toLowerCase(Locale.ROOT);
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                progress.setVisibility(View.GONE);
                refreshStatus();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                toast("უსაფრთხო კავშირის შეცდომა.");
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                openExternal(Uri.parse(url)));
    }

    private Intent buildFileIntent(WebChromeClient.FileChooserParams params) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType("*/*");

        ArrayList<String> types = new ArrayList<>();
        if (params != null && params.getAcceptTypes() != null) {
            for (String type : params.getAcceptTypes()) {
                if (type == null) continue;
                String value = type.trim();
                if (!value.isEmpty() && !types.contains(value)) types.add(value);
            }
        }
        if (types.size() == 1) {
            intent.setType(types.get(0));
        } else if (types.size() > 1) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
        }
        boolean multiple = params != null
                && params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        return Intent.createChooser(intent, "აირჩიე ფოტო ან ფაილი");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE_CHOOSER || fileCallback == null) return;

        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    result[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    private void showMenu() {
        String[] items = {
                "Termux ნებართვის მოთხოვნა",
                "დაწყვილების კოდი",
                "Bridge-ის გადატვირთვა",
                "ChatGPT მთავარი გვერდი",
                "Ursafe-ის სისტემური პარამეტრები"
        };
        new AlertDialog.Builder(this)
                .setTitle("Ursafe v0.7")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            requestTermuxPermission();
                            break;
                        case 1:
                            showPairingCode();
                            break;
                        case 2:
                            try {
                                BridgeForegroundService.start(this);
                                toast("Bridge გაშვებულია.");
                            } catch (Exception error) {
                                toast("Bridge ვერ გაეშვა: " + safe(error.getMessage()));
                            }
                            break;
                        case 3:
                            webView.loadUrl(CHAT_URL);
                            break;
                        case 4:
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                            break;
                        default:
                            break;
                    }
                })
                .show();
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

    private void requestTermuxPermission() {
        if (!isTermuxInstalled()) {
            toast("Termux ვერ მოიძებნა.");
            return;
        }
        if (checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            toast("RUN_COMMAND ნებართვა უკვე მინიჭებულია.");
            return;
        }
        requestPermissions(new String[]{TERMUX_PERMISSION}, REQUEST_TERMUX_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_TERMUX_PERMISSION) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        toast(granted ? "Termux ნებართვა მინიჭებულია." : "Termux ნებართვა არ მინიჭებულა.");
        refreshStatus();
    }

    private void refreshStatus() {
        if (statusText == null) return;
        boolean installed = isTermuxInstalled();
        boolean permission = checkSelfPermission(TERMUX_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
        statusText.setText("ფაილების ატვირთვა: მზადაა  •  Termux: "
                + (installed ? "OK" : "არ არის") + "  •  RUN_COMMAND: "
                + (permission ? "OK" : "საჭიროა"));
        if (bridgeBadge != null) {
            bridgeBadge.setText(permission ? "BRIDGE ON" : "BRIDGE");
        }
    }

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception error) {
            toast("ბმული ვერ გაიხსნა.");
        }
    }

    private TextView toolbarButton(String label) {
        TextView view = text(label, 24, false, Color.rgb(65, 68, 80));
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(Color.TRANSPARENT, 999));
        return view;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
