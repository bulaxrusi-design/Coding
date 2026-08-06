package com.ursafe.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    public static final String ACTION_TERMUX_RESULT = "com.ursafe.app.TERMUX_RESULT";

    private static final String CHATGPT_HOME = "https://chatgpt.com/";
    private static final String CHATGPT_LOGIN = "https://chatgpt.com/auth/login";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final int REQUEST_TERMUX_PERMISSION = 501;
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger(1000);

    private FrameLayout appRoot;
    private TextView statusView;
    private WebView webView;
    private ProgressBar webProgress;
    private TextView webTitle;
    private boolean receiverRegistered;
    private boolean chatVisible;

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
        getWindow().setStatusBarColor(Color.rgb(247, 248, 252));
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        appRoot = new FrameLayout(this);
        appRoot.setBackgroundColor(Color.rgb(247, 248, 252));
        setContentView(appRoot);
        showHome();
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

    @Override
    protected void onDestroy() {
        destroyWebView();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (chatVisible && webView != null) {
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                showHome();
            }
            return;
        }
        super.onBackPressed();
    }

    private void showHome() {
        chatVisible = false;
        destroyWebView();
        appRoot.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(24));
        appRoot.addView(scroll, matchParent());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        page.addView(createBrandHeader(), matchWrap());
        page.addView(space(22));

        LinearLayout hero = card(Color.WHITE, 26);
        hero.setPadding(dp(22), dp(24), dp(22), dp(22));
        hero.setElevation(dp(3));
        page.addView(hero, matchWrap());

        TextView eyebrow = text("URSAFE • PRIVATE SHELL", 12, true, Color.rgb(91, 69, 224));
        eyebrow.setLetterSpacing(0.12f);
        hero.addView(eyebrow, matchWrap());

        TextView heroTitle = text("შენი ChatGPT,\nერთ სივრცეში", 31, true, Color.rgb(21, 23, 31));
        heroTitle.setLineSpacing(0f, 0.98f);
        heroTitle.setPadding(0, dp(10), 0, dp(10));
        hero.addView(heroTitle, matchWrap());

        TextView heroCopy = text(
                "ოფიციალური ChatGPT გვერდი გაიხსნება Ursafe-ის შიგნით. სესია რჩება WebView-ში და აპი არ კითხულობს შენს პაროლს.",
                15, false, Color.rgb(91, 95, 110));
        heroCopy.setLineSpacing(dp(2), 1.12f);
        hero.addView(heroCopy, matchWrap());

        hero.addView(space(20));
        Button login = primaryButton("შესვლა ChatGPT ანგარიშით");
        login.setOnClickListener(v -> openChat(CHATGPT_LOGIN));
        hero.addView(login, matchWrap());

        hero.addView(space(10));
        Button continueButton = secondaryButton("ChatGPT-ის გახსნა");
        continueButton.setOnClickListener(v -> openChat(CHATGPT_HOME));
        hero.addView(continueButton, matchWrap());

        page.addView(space(18));

        LinearLayout features = new LinearLayout(this);
        features.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(features, matchWrap());

        features.addView(featureTile("შიდა ჩატი", "ChatGPT Ursafe-ში"), weighted());
        features.addView(horizontalSpace(10));
        features.addView(featureTile("Termux", "დაცული bridge"), weighted());

        page.addView(space(18));

        LinearLayout termuxCard = card(Color.rgb(31, 34, 45), 24);
        termuxCard.setPadding(dp(20), dp(20), dp(20), dp(20));
        termuxCard.setElevation(dp(2));
        page.addView(termuxCard, matchWrap());

        LinearLayout termuxHeader = new LinearLayout(this);
        termuxHeader.setGravity(Gravity.CENTER_VERTICAL);
        termuxCard.addView(termuxHeader, matchWrap());

        TextView prompt = text(">_", 24, true, Color.rgb(169, 151, 255));
        termuxHeader.addView(prompt, wrapWrap());

        TextView termuxTitle = text("Termux bridge", 21, true, Color.WHITE);
        termuxTitle.setPadding(dp(12), 0, 0, 0);
        termuxHeader.addView(termuxTitle, weighted());

        TextView badge = text("MVP", 11, true, Color.rgb(218, 211, 255));
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(rounded(Color.rgb(73, 63, 126), 999));
        termuxHeader.addView(badge, wrapWrap());

        TextView termuxCopy = text(
                "ჯერ აქტიურია მხოლოდ უსაფრთხო კავშირის ტესტი. თავისუფალი ბრძანებების მართვა შემდეგ ეტაპზე დაემატება.",
                14, false, Color.rgb(200, 203, 216));
        termuxCopy.setPadding(0, dp(12), 0, dp(14));
        termuxCopy.setLineSpacing(dp(2), 1.1f);
        termuxCard.addView(termuxCopy, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        termuxCard.addView(actions, matchWrap());

        Button permission = darkButton("ნებართვა");
        permission.setOnClickListener(v -> requestTermuxPermission());
        actions.addView(permission, weighted());

        actions.addView(horizontalSpace(10));

        Button test = darkButton("კავშირის ტესტი");
        test.setOnClickListener(v -> runTermuxTest());
        actions.addView(test, weighted());

        statusView = text("სტატუსი იტვირთება…", 13, false, Color.rgb(210, 213, 225));
        statusView.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusView.setBackground(rounded(Color.rgb(42, 46, 59), 14));
        statusView.setLineSpacing(dp(2), 1.05f);
        termuxCard.addView(space(12));
        termuxCard.addView(statusView, matchWrap());

        Button settingsButton = linkButton("Ursafe-ის სისტემური პარამეტრები");
        settingsButton.setTextColor(Color.rgb(184, 174, 255));
        settingsButton.setOnClickListener(v -> openAppSettings());
        termuxCard.addView(settingsButton, matchWrap());

        TextView footer = text("Ursafe v0.4 • Chat shell + Termux bridge", 12, false,
                Color.rgb(130, 134, 149));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(22), 0, 0);
        page.addView(footer, matchWrap());

        refreshStatus();
    }

    private View createBrandHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(com.ursafe.app.R.drawable.ic_ursafe_logo);
        logo.setContentDescription("Ursafe logo");
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        header.addView(logo, logoParams);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(13), 0, 0, 0);
        header.addView(titles, weighted());

        titles.addView(text("Ursafe", 27, true, Color.rgb(22, 24, 32)), matchWrap());
        titles.addView(text("AI workspace", 13, false, Color.rgb(112, 116, 132)), matchWrap());

        TextView version = text("v0.4", 12, true, Color.rgb(91, 69, 224));
        version.setGravity(Gravity.CENTER);
        version.setPadding(dp(10), dp(7), dp(10), dp(7));
        version.setBackground(rounded(Color.rgb(235, 231, 255), 999));
        header.addView(version, wrapWrap());

        return header;
    }

    private View featureTile(String title, String subtitle) {
        LinearLayout tile = card(Color.WHITE, 18);
        tile.setPadding(dp(16), dp(16), dp(16), dp(16));
        tile.setElevation(dp(1));
        tile.addView(text(title, 16, true, Color.rgb(30, 32, 42)), matchWrap());
        TextView small = text(subtitle, 12, false, Color.rgb(118, 122, 138));
        small.setPadding(0, dp(4), 0, 0);
        tile.addView(small, matchWrap());
        return tile;
    }

    private void openChat(String initialUrl) {
        chatVisible = true;
        appRoot.removeAllViews();

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.WHITE);
        appRoot.addView(shell, matchParent());

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(5), dp(8), dp(5));
        toolbar.setBackgroundColor(Color.WHITE);
        toolbar.setElevation(dp(3));
        shell.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        TextView back = iconButton("‹", "უკან");
        back.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            } else {
                showHome();
            }
        });
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));

        ImageView logo = new ImageView(this);
        logo.setImageResource(com.ursafe.app.R.drawable.ic_ursafe_logo);
        logo.setContentDescription("Ursafe");
        toolbar.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));

        webTitle = text("Ursafe Chat", 17, true, Color.rgb(25, 27, 36));
        webTitle.setSingleLine(true);
        webTitle.setPadding(dp(10), 0, dp(8), 0);
        toolbar.addView(webTitle, weighted());

        TextView refresh = iconButton("↻", "განახლება");
        refresh.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });
        toolbar.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView home = iconButton("⌂", "მთავარი");
        home.setOnClickListener(v -> showHome());
        toolbar.addView(home, new LinearLayout.LayoutParams(dp(44), dp(44)));

        webProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        webProgress.setMax(100);
        webProgress.setProgress(5);
        shell.addView(webProgress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        configureWebView(webView);
        shell.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        webView.loadUrl(initialUrl);
    }

    private void configureWebView(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " Ursafe/0.4");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(view, true);

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView webView, int newProgress) {
                if (webProgress == null) return;
                webProgress.setProgress(newProgress);
                webProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView webView, String title) {
                if (webTitle == null || title == null || title.trim().isEmpty()) return;
                String clean = title.replace(" | OpenAI", "").replace("ChatGPT", "Ursafe Chat");
                webTitle.setText(clean.length() > 28 ? clean.substring(0, 28) + "…" : clean);
            }
        });

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                return routeUrl(webView, request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, String url) {
                return routeUrl(webView, Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView webView, String url) {
                CookieManager.getInstance().flush();
                super.onPageFinished(webView, url);
            }

            @Override
            public void onReceivedSslError(
                    WebView webView,
                    SslErrorHandler handler,
                    SslError error) {
                handler.cancel();
                Toast.makeText(MainActivity.this,
                        "უსაფრთხო კავშირის შემოწმება ვერ დასრულდა.",
                        Toast.LENGTH_LONG).show();
            }
        });

        view.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimeType,
                    long contentLength) {
                openExternal(Uri.parse(url));
            }
        });
    }

    private boolean routeUrl(WebView target, Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if ("https".equals(scheme) || "http".equals(scheme)) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
            if (isInternalHost(host)) {
                target.loadUrl(uri.toString());
            } else {
                openExternal(uri);
            }
            return true;
        }

        if ("mailto".equals(scheme) || "tel".equals(scheme)
                || "intent".equals(scheme) || "market".equals(scheme)) {
            openExternal(uri);
            return true;
        }
        return false;
    }

    private boolean isInternalHost(String host) {
        return host.equals("chatgpt.com")
                || host.endsWith(".chatgpt.com")
                || host.equals("openai.com")
                || host.endsWith(".openai.com")
                || host.equals("oaistatic.com")
                || host.endsWith(".oaistatic.com")
                || host.equals("oaiusercontent.com")
                || host.endsWith(".oaiusercontent.com")
                || host.equals("auth0.com")
                || host.endsWith(".auth0.com")
                || host.equals("accounts.google.com")
                || host.endsWith(".google.com")
                || host.equals("appleid.apple.com")
                || host.endsWith(".apple.com");
    }

    private void openExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, "ბმულის გახსნა ვერ მოხერხდა.", Toast.LENGTH_SHORT).show();
        }
    }

    private void destroyWebView() {
        if (webView == null) return;
        try {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        } catch (Exception ignored) {
            // Best-effort cleanup.
        }
        webView = null;
        webProgress = null;
        webTitle = null;
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
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
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
        PendingIntent pendingIntent =
                PendingIntent.getService(this, requestId, resultIntent, flags);

        Intent command = new Intent();
        command.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
        command.setAction("com.termux.RUN_COMMAND");
        command.putExtra(
                "com.termux.RUN_COMMAND_PATH",
                "/data/data/com.termux/files/usr/bin/bash");
        command.putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                new String[]{"-lc", "printf 'Ursafe ↔ Termux OK\\n'"});
        command.putExtra(
                "com.termux.RUN_COMMAND_WORKDIR",
                "/data/data/com.termux/files/home");
        command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        command.putExtra(
                "com.termux.RUN_COMMAND_COMMAND_LABEL",
                "Ursafe connection test");
        command.putExtra(
                "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION",
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
        String installed = isTermuxInstalled() ? "დაკავშირებულია" : "არ არის დაყენებული";
        String permission =
                checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED
                        ? "RUN_COMMAND მინიჭებულია"
                        : "RUN_COMMAND არ არის მინიჭებული";
        setStatus("Termux: " + installed + "\n" + permission);
    }

    private void setStatus(String value) {
        if (statusView != null) statusView.setText(value);
    }

    private LinearLayout card(int color, int radiusDp) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(rounded(color, radiusDp));
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        if (bold) view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        else view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return view;
    }

    private Button primaryButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(Color.rgb(91, 69, 224), 16));
        button.setElevation(dp(2));
        button.setMinHeight(dp(54));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(Color.rgb(52, 44, 104));
        GradientDrawable background = rounded(Color.rgb(243, 241, 255), 16);
        background.setStroke(dp(1), Color.rgb(218, 211, 255));
        button.setBackground(background);
        button.setMinHeight(dp(52));
        return button;
    }

    private Button darkButton(String label) {
        Button button = baseButton(label);
        button.setTextSize(13);
        button.setTextColor(Color.WHITE);
        GradientDrawable background = rounded(Color.rgb(55, 59, 74), 14);
        background.setStroke(dp(1), Color.rgb(83, 88, 106));
        button.setBackground(background);
        button.setMinHeight(dp(48));
        return button;
    }

    private Button linkButton(String label) {
        Button button = baseButton(label);
        button.setTextSize(13);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinHeight(dp(42));
        return button;
    }

    private Button baseButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setStateListAnimator(null);
        return button;
    }

    private TextView iconButton(String glyph, String description) {
        TextView button = text(glyph, 25, false, Color.rgb(48, 51, 64));
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackground(rounded(Color.TRANSPARENT, 999));
        button.setOnLongClickListener(v -> {
            Toast.makeText(this, description, Toast.LENGTH_SHORT).show();
            return true;
        });
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private Space space(int valueDp) {
        Space space = new Space(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(valueDp)));
        return space;
    }

    private Space horizontalSpace(int valueDp) {
        Space space = new Space(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(dp(valueDp), 1));
        return space;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
