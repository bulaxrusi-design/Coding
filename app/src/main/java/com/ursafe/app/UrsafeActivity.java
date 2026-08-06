package com.ursafe.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class UrsafeActivity extends Activity {
    public static final String ACTION_TERMUX_RESULT = "com.ursafe.app.TERMUX_RESULT_V06";
    public static final String PREFS = "ursafe_bridge";
    public static final String PREF_PENDING_RESULT = "pending_result";

    private static final String CHAT_URL = "https://chatgpt.com/";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final int REQUEST_TERMUX_PERMISSION = 601;
    private static final int MAX_COMMAND_CHARS = 8000;
    private static final int MAX_CHAT_RESULT_CHARS = 12000;
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger(6000);

    private WebView webView;
    private ProgressBar progress;
    private TextView bridgeBadge;
    private TextView statusText;
    private boolean receiverRegistered;
    private boolean bridgeEnabled;
    private String queuedChatText;
    private String lastDetectedId = "";

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String resultText = safe(intent.getStringExtra("chat_result"));
            if (resultText.isEmpty()) resultText = buildResultText(intent);
            handleTermuxResult(resultText);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(250, 250, 252));
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        bridgeEnabled = prefs().getBoolean("bridge_enabled", false);
        buildUi();
        loadPendingResult();
        webView.loadUrl(CHAT_URL);
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
        loadPendingResult();
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
        if (webView != null) {
            webView.removeJavascriptInterface("UrsafeNative");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // OAuth-ის ძველ გვერდებში აღარ დავდივართ — Back აპს ფონზე უშვებს.
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
        configureWebView();
        page.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        statusText = text("Termux bridge მზადდება…", 12, false, Color.rgb(82, 86, 100));
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(dp(12), 0, dp(12), 0);
        statusText.setBackground(rounded(Color.rgb(246, 246, 250), 0));
        page.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
    }

    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(5), dp(8), dp(5));
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(4));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_ursafe_logo);
        logo.setContentDescription("Ursafe");
        bar.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, 0, 0);
        titles.addView(text("Ursafe Chat", 16, true, Color.rgb(25, 27, 36)));
        titles.addView(text("ChatGPT Plus shell", 11, false, Color.rgb(112, 116, 130)));
        bar.addView(titles, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        bridgeBadge = text(bridgeEnabled ? "BRIDGE ON" : "BRIDGE OFF", 10, true,
                bridgeEnabled ? Color.rgb(21, 122, 77) : Color.rgb(116, 119, 132));
        bridgeBadge.setGravity(Gravity.CENTER);
        bridgeBadge.setPadding(dp(10), dp(7), dp(10), dp(7));
        bridgeBadge.setBackground(rounded(
                bridgeEnabled ? Color.rgb(225, 247, 237) : Color.rgb(240, 241, 245), 999));
        bridgeBadge.setOnClickListener(v -> showBridgeMenu());
        bar.addView(bridgeBadge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView reload = toolbarButton("↻");
        reload.setContentDescription("განახლება");
        reload.setOnClickListener(v -> webView.reload());
        bar.addView(reload, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView menu = toolbarButton("⋮");
        menu.setContentDescription("მენიუ");
        menu.setOnClickListener(v -> showBridgeMenu());
        bar.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return bar;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = safe(uri.getScheme()).toLowerCase(Locale.ROOT);
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    openExternal(uri);
                    return true;
                }
                if (!isChatGptHost(uri.getHost())) removeNativeBridge();
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                Uri uri = Uri.parse(url);
                if (isChatGptHost(uri.getHost())) {
                    installNativeBridge();
                    installPageObserver();
                    if (bridgeEnabled && !prefs().getBoolean("setup_prompt_sent", false)) {
                        showFirstBridgePrompt();
                    }
                    flushQueuedChatText();
                } else {
                    removeNativeBridge();
                }
                refreshStatus();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                toast("უსაფრთხო კავშირის შეცდომა.");
            }
        });
    }

    private boolean isChatGptHost(String host) {
        String value = safe(host).toLowerCase(Locale.ROOT);
        return "chatgpt.com".equals(value) || value.endsWith(".chatgpt.com");
    }

    private void installNativeBridge() {
        try { webView.removeJavascriptInterface("UrsafeNative"); } catch (Exception ignored) { }
        webView.addJavascriptInterface(new ChatBridge(), "UrsafeNative");
    }

    private void removeNativeBridge() {
        try { webView.removeJavascriptInterface("UrsafeNative"); } catch (Exception ignored) { }
    }

    private void installPageObserver() {
        String script =
                "(function(){" +
                "if(window.__ursafeObserverInstalled){return 'already';}" +
                "window.__ursafeObserverInstalled=true;" +
                "window.__ursafeSeen=window.__ursafeSeen||{};" +
                "function hash(s){var h=0;for(var i=0;i<s.length;i++){h=((h<<5)-h)+s.charCodeAt(i);h|=0;}return String(h);}" +
                "function scan(){" +
                "document.querySelectorAll('pre').forEach(function(pre){" +
                "var parent=pre.closest('[data-message-author-role]');" +
                "if(parent&&parent.getAttribute('data-message-author-role')!=='assistant'){return;}" +
                "var t=(pre.innerText||pre.textContent||'').trim();" +
                "var marker='URSAFE_TERMUX';" +
                "var at=t.indexOf(marker);if(at<0){return;}" +
                "var body=t.substring(at+marker.length).trim();" +
                "var id=hash(body);if(window.__ursafeSeen[id]){return;}" +
                "window.__ursafeSeen[id]=true;" +
                "try{window.UrsafeNative.proposeCommand(body,id);}catch(e){}" +
                "});}" +
                "new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true,characterData:true});" +
                "setInterval(scan,1800);scan();return 'ok';})();";
        webView.evaluateJavascript(script, null);
    }

    private final class ChatBridge {
        @JavascriptInterface
        public void proposeCommand(String body, String id) {
            runOnUiThread(() -> processCommandProposal(body, id));
        }
    }

    private void processCommandProposal(String body, String id) {
        if (!bridgeEnabled || body == null || body.trim().isEmpty()) return;
        if (id != null && id.equals(lastDetectedId)) return;

        String command;
        String reason = "";
        try {
            JSONObject json = new JSONObject(body.trim());
            command = safe(json.optString("command")).trim();
            reason = safe(json.optString("reason")).trim();
        } catch (Exception ignored) {
            command = body.trim();
        }

        if (command.length() > MAX_COMMAND_CHARS) {
            toast("ბრძანება ზედმეტად გრძელია და დაიბლოკა.");
            return;
        }
        if (command.isEmpty()) return;

        lastDetectedId = safe(id);
        showCommandApproval(command, reason);
    }

    private void showCommandApproval(String command, String reason) {
        String risk = commandRisk(command);
        StringBuilder message = new StringBuilder();
        if (!reason.isEmpty()) message.append(reason).append("\n\n");
        if (!risk.isEmpty()) message.append("გაფრთხილება: ").append(risk).append("\n\n");
        message.append(command);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Termux ბრძანების დადასტურება")
                .setMessage(message.toString())
                .setNegativeButton("გაუქმება", (d, which) ->
                        queueChatText("URSAFE_TERMUX_CANCELLED\nმომხმარებელმა ბრძანება არ დაადასტურა."))
                .setNeutralButton("კოპირება", null)
                .setPositiveButton("გაშვება", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Termux command", command));
                toast("ბრძანება დაკოპირდა.");
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (isBlockedCommand(command)) {
                    toast("ეს კატასტროფული ბრძანება Ursafe-მა დაბლოკა.");
                    return;
                }
                dialog.dismiss();
                runTermuxCommand(command, "assistant");
            });
        });
        dialog.show();
    }

    private boolean isBlockedCommand(String command) {
        String n = command.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return n.matches("(^|.*[;&|])\\s*(su|sudo)(\\s|$).*")
                || n.contains("rm -rf / ")
                || n.endsWith("rm -rf /")
                || n.contains("mkfs.")
                || n.contains("mkswap ")
                || n.matches(".*dd\\s+.*of=/dev/.*")
                || n.contains("setenforce 0")
                || n.matches("(^|.*[;&|])\\s*(reboot|shutdown|poweroff|halt)(\\s|$).*");
    }

    private String commandRisk(String command) {
        String n = command.toLowerCase(Locale.ROOT);
        if (isBlockedCommand(command)) return "სისტემის დაზიანების მაღალი რისკი — შესრულება დაბლოკილია.";
        if (n.contains(" rm ") || n.startsWith("rm ") || n.contains("chmod ")
                || n.contains("curl ") || n.contains("wget ")
                || n.contains("pkg install") || n.contains("apt install")) {
            return "ბრძანება შეიძლება ცვლიდეს ფაილებს, უფლებებს, პაკეტებს ან ქსელს.";
        }
        return "";
    }

    private void runTermuxCommand(String command, String kind) {
        if (!isTermuxInstalled()) {
            toast("Termux ვერ მოიძებნა.");
            queueChatText("URSAFE_TERMUX_ERROR\nTermux მოწყობილობაზე ვერ მოიძებნა.");
            return;
        }
        if (checkSelfPermission(TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{TERMUX_PERMISSION}, REQUEST_TERMUX_PERMISSION);
            toast("ჯერ მიანიჭე Termux RUN_COMMAND ნებართვა.");
            return;
        }

        int requestId = REQUEST_IDS.incrementAndGet();
        Intent resultIntent = new Intent(this, BridgeResultService.class)
                .putExtra("request_id", requestId)
                .putExtra("request_kind", kind)
                .putExtra("command", command);

        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pendingIntent = PendingIntent.getService(this, requestId, resultIntent, flags);

        Intent run = new Intent();
        run.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
        run.setAction("com.termux.RUN_COMMAND");
        run.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        run.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", command});
        run.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        run.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        run.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Ursafe assistant task");
        run.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION",
                "User-approved command from the Ursafe Chat bridge.");
        run.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent);

        try {
            startService(run);
            setStatus("Termux მუშაობს ფონურად…");
            toast("ბრძანება Termux-ში გაიგზავნა.");
        } catch (SecurityException error) {
            setStatus("Termux ნებართვა აკლია.");
            queueChatText("URSAFE_TERMUX_ERROR\nRUN_COMMAND ნებართვის შეცდომა: "
                    + safe(error.getMessage()));
        } catch (Exception error) {
            setStatus("Termux bridge-ის შეცდომა.");
            queueChatText("URSAFE_TERMUX_ERROR\n" + safe(error.getMessage()));
        }
    }

    private void handleTermuxResult(String resultText) {
        prefs().edit().remove(PREF_PENDING_RESULT).apply();
        setStatus("Termux დასრულდა — შედეგი ჩატში იგზავნება.");
        queueChatText(truncate(resultText, MAX_CHAT_RESULT_CHARS));
    }

    private void queueChatText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        queuedChatText = text;
        flushQueuedChatText();
    }

    private void flushQueuedChatText() {
        if (queuedChatText == null || queuedChatText.trim().isEmpty()) return;
        Uri uri = Uri.parse(safe(webView.getUrl()));
        if (!isChatGptHost(uri.getHost())) return;

        String jsonText = JSONObject.quote(queuedChatText);
        String script =
                "(function(t){" +
                "var e=document.querySelector('#prompt-textarea');if(!e){return 'no-editor';}" +
                "e.focus();" +
                "if(e.tagName==='TEXTAREA'||e.tagName==='INPUT'){" +
                "var s=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');" +
                "if(s&&s.set){s.set.call(e,t);}else{e.value=t;}" +
                "}else{e.innerHTML='';var p=document.createElement('p');p.textContent=t;e.appendChild(p);}" +
                "e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:t}));" +
                "e.dispatchEvent(new Event('change',{bubbles:true}));" +
                "setTimeout(function(){" +
                "var b=document.querySelector('button[data-testid=\"send-button\"]');" +
                "if(!b){b=Array.from(document.querySelectorAll('button')).find(function(x){" +
                "var a=(x.getAttribute('aria-label')||'').toLowerCase();" +
                "return a.indexOf('send')>=0||a.indexOf('გაგზავ')>=0;});}" +
                "if(b&&!b.disabled){b.click();}},500);return 'ok';})((" + jsonText + "));";

        webView.evaluateJavascript(script, value -> {
            if (value != null && value.contains("ok")) {
                queuedChatText = null;
                setStatus("Termux შედეგი ჩატში გაიგზავნა.");
            } else {
                setStatus("შედეგი მზადაა — გახსენი ჩატი.");
            }
        });
    }

    private void showFirstBridgePrompt() {
        new AlertDialog.Builder(this)
                .setTitle("Termux bridge-ის ჩართვა")
                .setMessage("Ursafe ჩატში გააგზავნის ერთ setup შეტყობინებას. შემდეგ ChatGPT "
                        + "შეძლებს შემოგთავაზოს Termux ბრძანება; შესრულებამდე ყოველთვის დაინახავ "
                        + "ბრძანებას და თავად დაადასტურებ.")
                .setNegativeButton("მოგვიანებით", null)
                .setPositiveButton("ჩართვა", (dialog, which) -> sendBridgeSetupPrompt())
                .show();
    }

    private void sendBridgeSetupPrompt() {
        bridgeEnabled = true;
        prefs().edit().putBoolean("bridge_enabled", true)
                .putBoolean("setup_prompt_sent", true).apply();
        updateBridgeBadge();

        String prompt = "Ursafe Termux Bridge v1 is active on my authorized Android device. "
                + "When I explicitly ask for a local Termux task and a command is genuinely needed, "
                + "give a brief explanation and then exactly one fenced code block whose contents are:\n"
                + "URSAFE_TERMUX\n"
                + "{\"command\":\"<one bash -lc command>\",\"reason\":\"<why it is needed>\"}\n"
                + "Do not emit the block unless execution is needed. Do not propose destructive wiping, "
                + "persistence, credential theft, surveillance, or bypassing consent. The Ursafe app will "
                + "show me the exact command and require my approval. After execution it will send back "
                + "URSAFE_TERMUX_RESULT with stdout, stderr and exit code.";
        queueChatText(prompt);
    }

    private void showBridgeMenu() {
        String[] items = {
                bridgeEnabled ? "Bridge-ის გამორთვა" : "Bridge-ის ჩართვა",
                "Termux კავშირის ტესტი",
                "Termux ნებართვის მოთხოვნა",
                "ChatGPT მთავარი გვერდი",
                "Ursafe-ის სისტემური პარამეტრები"
        };
        new AlertDialog.Builder(this)
                .setTitle("Ursafe")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            if (bridgeEnabled) {
                                bridgeEnabled = false;
                                prefs().edit().putBoolean("bridge_enabled", false).apply();
                                updateBridgeBadge();
                                toast("Bridge გამორთულია.");
                            } else {
                                sendBridgeSetupPrompt();
                            }
                            break;
                        case 1:
                            runTermuxCommand("printf 'Ursafe ↔ Termux OK\\n'; pwd", "test");
                            break;
                        case 2:
                            requestTermuxPermission();
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
                }).show();
    }

    private void requestTermuxPermission() {
        if (!isTermuxInstalled()) {
            toast("ჯერ დააყენე ოფიციალური Termux.");
            return;
        }
        if (checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            toast("RUN_COMMAND ნებართვა უკვე მინიჭებულია.");
            return;
        }
        requestPermissions(new String[]{TERMUX_PERMISSION}, REQUEST_TERMUX_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_TERMUX_PERMISSION) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        toast(granted ? "Termux ნებართვა მინიჭებულია." : "Termux ნებართვა არ მინიჭებულა.");
        refreshStatus();
    }

    private void loadPendingResult() {
        String pending = prefs().getString(PREF_PENDING_RESULT, "");
        if (!pending.isEmpty()) {
            queuedChatText = pending;
            flushQueuedChatText();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void refreshStatus() {
        if (statusText == null) return;
        boolean installed = isTermuxInstalled();
        boolean permission = checkSelfPermission(TERMUX_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
        statusText.setText((bridgeEnabled ? "Bridge ON" : "Bridge OFF") + "  •  Termux: "
                + (installed ? "დაყენებულია" : "არ არის") + "  •  RUN_COMMAND: "
                + (permission ? "OK" : "საჭიროა"));
        updateBridgeBadge();
    }

    private void setStatus(String value) {
        if (statusText != null) statusText.setText(value);
    }

    private void updateBridgeBadge() {
        if (bridgeBadge == null) return;
        bridgeBadge.setText(bridgeEnabled ? "BRIDGE ON" : "BRIDGE OFF");
        bridgeBadge.setTextColor(bridgeEnabled
                ? Color.rgb(21, 122, 77) : Color.rgb(116, 119, 132));
        bridgeBadge.setBackground(rounded(bridgeEnabled
                ? Color.rgb(225, 247, 237) : Color.rgb(240, 241, 245), 999));
    }

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private String buildResultText(Intent intent) {
        String command = safe(intent.getStringExtra("command"));
        String stdout = safe(intent.getStringExtra("stdout"));
        String stderr = safe(intent.getStringExtra("stderr"));
        int exitCode = intent.getIntExtra("exit_code", -1);
        StringBuilder out = new StringBuilder();
        out.append("URSAFE_TERMUX_RESULT\n");
        out.append("exit_code=").append(exitCode).append("\n");
        if (!command.isEmpty()) out.append("command:\n").append(command).append("\n");
        if (!stdout.isEmpty()) out.append("stdout:\n").append(stdout).append("\n");
        if (!stderr.isEmpty()) out.append("stderr:\n").append(stderr).append("\n");
        return truncate(out.toString(), MAX_CHAT_RESULT_CHARS);
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

    private static String truncate(String value, int max) {
        if (value.length() <= max) return value;
        return value.substring(0, max) + "\n[…შედეგი შემოკლებულია…]";
    }
}
