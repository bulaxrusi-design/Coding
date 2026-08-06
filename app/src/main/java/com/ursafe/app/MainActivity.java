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
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    public static final String ACTION_TERMUX_RESULT = "com.ursafe.app.TERMUX_RESULT";
    private static final String CHATGPT_HOME = "https://chatgpt.com/";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final int REQUEST_TERMUX_PERMISSION = 501;
    private static final int MAX_COMMAND_LENGTH = 4000;
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger(2000);

    private static final Pattern[] BLOCKED_COMMANDS = new Pattern[]{
            Pattern.compile("(^|[;&|]\\s*)(su|sudo)(\\s|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brm\\s+[^\\n]*-[^\\n]*r[^\\n]*f[^\\n]*\\s+/(\\s|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(mkfs|mkswap)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdd\\b[^\\n]*\\bof=/dev/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(reboot|shutdown|poweroff|halt)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsetenforce\\s+0\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(curl|wget)\\b[^\\n|]*\\|\\s*(sh|bash)\\b", Pattern.CASE_INSENSITIVE)
    };

    private WebView webView;
    private ProgressBar progress;
    private FrameLayout contentFrame;
    private View termuxPanel;
    private TextView titleView;
    private TextView termuxStatus;
    private TextView resultView;
    private EditText commandInput;
    private boolean receiverRegistered;
    private boolean termuxVisible;
    private boolean clearHistoryWhenHomeLoads;
    private String lastTermuxResult = "";

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = safe(intent.getStringExtra("message"));
            int exitCode = intent.getIntExtra("exit_code", -1);
            String requestKind = safe(intent.getStringExtra("request_kind"));
            lastTermuxResult = "Termux შედეგი (exit=" + exitCode + ")\n" + message;
            if (resultView != null) resultView.setText(lastTermuxResult);
            setTermuxStatus(requestKind.equals("test")
                    ? "კავშირის ტესტი დასრულდა."
                    : "ბრძანება დასრულდა. შედეგი მზადაა ჩატში ჩასასმელად.");
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
        buildUi();
        webView.loadUrl(CHATGPT_HOME);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ACTION_TERMUX_RESULT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(resultReceiver, filter);
        receiverRegistered = true;
        refreshTermuxStatus();
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
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (termuxVisible) {
            showChat();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 248, 252));
        setContentView(root);
        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(58)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        contentFrame = new FrameLayout(this);
        root.addView(contentFrame, new LinearLayout.LayoutParams(-1, 0, 1f));
        webView = new WebView(this);
        configureWebView();
        contentFrame.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        termuxPanel = buildTermuxPanel();
        termuxPanel.setVisibility(View.GONE);
        contentFrame.addView(termuxPanel, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildBottomBar(), new LinearLayout.LayoutParams(-1, dp(64)));
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(5), dp(8), dp(5));
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(3));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_ursafe_logo);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        titleView = text("Ursafe • ChatGPT", 17, true, Color.rgb(25, 27, 36));
        titleView.setPadding(dp(10), 0, 0, 0);
        bar.addView(titleView, weighted());
        Button home = compactButton("⌂");
        home.setOnClickListener(v -> openChatHome());
        bar.addView(home, new LinearLayout.LayoutParams(dp(44), dp(44)));
        Button refresh = compactButton("↻");
        refresh.setOnClickListener(v -> { showChat(); webView.reload(); });
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return bar;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(8), dp(7), dp(8), dp(7));
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(8));
        Button chat = navButton("ჩატი");
        chat.setOnClickListener(v -> showChat());
        bar.addView(chat, weighted());
        Button termux = navButton("Termux");
        termux.setOnClickListener(v -> showTermux());
        bar.addView(termux, weighted());
        Button result = navButton("შედეგი → ჩატში");
        result.setOnClickListener(v -> insertResultIntoChat());
        bar.addView(result, weighted());
        return bar;
    }

    private View buildTermuxPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 248, 252));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(rounded(Color.rgb(31, 34, 45), 24));
        page.addView(card, matchWrap());
        card.addView(text(">_  Termux bridge", 24, true, Color.WHITE), matchWrap());
        TextView copy = text("ჩატი ფონზე რჩება. ბოლო code block ამოიღე, გადაამოწმე, დაადასტურე და გაუშვი Termux-ში.", 14, false, Color.rgb(203, 206, 219));
        copy.setPadding(0, dp(10), 0, dp(16));
        card.addView(copy, matchWrap());

        termuxStatus = text("სტატუსი იტვირთება…", 13, false, Color.rgb(218, 211, 255));
        termuxStatus.setPadding(dp(13), dp(11), dp(13), dp(11));
        termuxStatus.setBackground(rounded(Color.rgb(50, 53, 68), 14));
        card.addView(termuxStatus, matchWrap());

        commandInput = new EditText(this);
        commandInput.setHint("ბრძანება გამოჩნდება აქ…");
        commandInput.setTextColor(Color.WHITE);
        commandInput.setHintTextColor(Color.rgb(148, 152, 168));
        commandInput.setTextSize(14);
        commandInput.setTypeface(Typeface.MONOSPACE);
        commandInput.setGravity(Gravity.TOP | Gravity.START);
        commandInput.setMinLines(5);
        commandInput.setMaxLines(12);
        commandInput.setPadding(dp(14), dp(14), dp(14), dp(14));
        commandInput.setBackground(rounded(Color.rgb(42, 46, 59), 16));
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.topMargin = dp(14);
        card.addView(commandInput, inputParams);

        LinearLayout row1 = new LinearLayout(this);
        row1.setPadding(0, dp(12), 0, 0);
        card.addView(row1, matchWrap());
        Button extract = darkButton("ბოლო კოდის აღება");
        extract.setOnClickListener(v -> extractLatestCodeBlock());
        row1.addView(extract, weighted());
        row1.addView(horizontalSpace());
        Button run = primaryButton("გაშვება");
        run.setOnClickListener(v -> confirmAndRunCommand());
        row1.addView(run, weighted());

        LinearLayout row2 = new LinearLayout(this);
        row2.setPadding(0, dp(10), 0, 0);
        card.addView(row2, matchWrap());
        Button permission = darkButton("ნებართვა");
        permission.setOnClickListener(v -> requestTermuxPermission());
        row2.addView(permission, weighted());
        row2.addView(horizontalSpace());
        Button test = darkButton("კავშირის ტესტი");
        test.setOnClickListener(v -> runTermux("printf 'Ursafe ↔ Termux OK\\n'", "test"));
        row2.addView(test, weighted());

        TextView resultTitle = text("ბოლო შედეგი", 15, true, Color.WHITE);
        resultTitle.setPadding(0, dp(18), 0, dp(8));
        card.addView(resultTitle, matchWrap());
        resultView = text("ჯერ შედეგი არ არის.", 13, false, Color.rgb(205, 208, 220));
        resultView.setTypeface(Typeface.MONOSPACE);
        resultView.setTextIsSelectable(true);
        resultView.setPadding(dp(13), dp(12), dp(13), dp(12));
        resultView.setBackground(rounded(Color.rgb(42, 46, 59), 14));
        card.addView(resultView, matchWrap());
        Button send = primaryButton("შედეგის ჩატში ჩასმა");
        send.setOnClickListener(v -> insertResultIntoChat());
        LinearLayout.LayoutParams sendParams = matchWrap();
        sendParams.topMargin = dp(12);
        card.addView(send, sendParams);
        return scroll;
    }

    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = safe(uri.getScheme()).toLowerCase(Locale.ROOT);
                if (scheme.equals("http") || scheme.equals("https")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                catch (Exception ignored) { toast("ბმულის გახსნა ვერ მოხერხდა."); }
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (clearHistoryWhenHomeLoads && url != null && url.startsWith(CHATGPT_HOME)) {
                    view.clearHistory();
                    clearHistoryWhenHomeLoads = false;
                }
            }
        });
    }

    private void showChat() {
        termuxVisible = false;
        termuxPanel.setVisibility(View.GONE);
        titleView.setText("Ursafe • ChatGPT");
    }

    private void showTermux() {
        termuxVisible = true;
        termuxPanel.setVisibility(View.VISIBLE);
        termuxPanel.bringToFront();
        titleView.setText("Ursafe • Termux");
        refreshTermuxStatus();
    }

    private void openChatHome() {
        showChat();
        clearHistoryWhenHomeLoads = true;
        webView.loadUrl(CHATGPT_HOME);
    }

    private void extractLatestCodeBlock() {
        showChat();
        String script = "(function(){var n=document.querySelectorAll('[data-message-author-role=\\\"assistant\\\"] pre code');if(!n.length)n=document.querySelectorAll('pre code,pre');if(!n.length)return '';return (n[n.length-1].innerText||n[n.length-1].textContent||'').trim();})()";
        webView.evaluateJavascript(script, value -> {
            String command = decodeJavascriptString(value).trim();
            if (command.isEmpty()) { toast("ბოლო კოდის ბლოკი ვერ მოიძებნა."); return; }
            if (command.length() > MAX_COMMAND_LENGTH) { toast("ბრძანება ზედმეტად გრძელია."); return; }
            commandInput.setText(command);
            commandInput.setSelection(command.length());
            showTermux();
            setTermuxStatus("კოდი ამოღებულია. გადაამოწმე და დააჭირე „გაშვებას“.");
        });
    }

    private void confirmAndRunCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) { toast("ჯერ ბრძანება შეიყვანე ან ჩატიდან ამოიღე."); return; }
        String reason = blockedReason(command);
        if (reason != null) {
            new AlertDialog.Builder(this).setTitle("ბრძანება დაბლოკილია").setMessage(reason).setPositiveButton("გასაგებია", null).show();
            return;
        }
        TextView preview = text(command, 13, false, Color.rgb(32, 34, 42));
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(18), dp(8), dp(18), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("გავუშვათ Termux-ში?")
                .setMessage("შეამოწმე ბრძანების სრული ტექსტი:")
                .setView(preview)
                .setNegativeButton("გაუქმება", null)
                .setPositiveButton("დადასტურება და გაშვება", (d, w) -> runTermux(command, "command"))
                .show();
    }

    private void runTermux(String commandText, String requestKind) {
        if (!isTermuxInstalled()) { setTermuxStatus("Termux ვერ მოიძებნა."); return; }
        if (checkSelfPermission(TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            setTermuxStatus("ჯერ საჭიროა RUN_COMMAND ნებართვა.");
            requestTermuxPermission();
            return;
        }
        int requestId = REQUEST_IDS.incrementAndGet();
        Intent resultIntent = new Intent(this, CommandResultService.class)
                .putExtra("request_id", requestId).putExtra("request_kind", requestKind);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pendingIntent = PendingIntent.getService(this, requestId, resultIntent, flags);
        Intent command = new Intent();
        command.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
        command.setAction("com.termux.RUN_COMMAND");
        command.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        command.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", commandText});
        command.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        command.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Ursafe command");
        command.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Command approved by the user in Ursafe.");
        command.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent);
        try {
            startService(command);
            setTermuxStatus(requestKind.equals("test") ? "კავშირის ტესტი გაიგზავნა…" : "ბრძანება გაიგზავნა…");
        } catch (Exception error) {
            setTermuxStatus("Termux შეცდომა: " + safe(error.getMessage()));
        }
    }

    private void insertResultIntoChat() {
        if (lastTermuxResult.trim().isEmpty()) { toast("ჯერ Termux-ის შედეგი არ არის."); showTermux(); return; }
        showChat();
        String payload = "[Ursafe Termux bridge]\n" + lastTermuxResult + "\n\nგაანალიზე შედეგი და შემომთავაზე შემდეგი უსაფრთხო ნაბიჯი. ბრძანება ცალკე code block-ში დაწერე.";
        String quoted = JSONObject.quote(payload);
        String script = "(function(){var e=document.querySelector('#prompt-textarea')||document.querySelector('textarea')||document.querySelector('[contenteditable=\\\"true\\\"]');if(!e)return 'NO_INPUT';var t=" + quoted + ";e.focus();if(e.tagName==='TEXTAREA'||e.tagName==='INPUT'){var p=Object.getPrototypeOf(e),d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)d.set.call(e,t);else e.value=t;e.dispatchEvent(new Event('input',{bubbles:true}));}else{e.textContent=t;e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:t}));}return 'OK';})()";
        webView.evaluateJavascript(script, value -> {
            if ("OK".equals(decodeJavascriptString(value))) toast("შედეგი ჩატში ჩაისვა — გაგზავნას შენ დააჭირე.");
            else { copyToClipboard(payload); toast("შედეგი დაკოპირდა — ჩასვი ჩატში."); }
        });
    }

    private void requestTermuxPermission() {
        if (!isTermuxInstalled()) { setTermuxStatus("Termux ვერ მოიძებნა."); return; }
        if (checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            setTermuxStatus("RUN_COMMAND ნებართვა უკვე მინიჭებულია.");
            return;
        }
        requestPermissions(new String[]{TERMUX_PERMISSION}, REQUEST_TERMUX_PERMISSION);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_TERMUX_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            setTermuxStatus(granted ? "RUN_COMMAND ნებართვა მინიჭებულია." : "ნებართვა არ მინიჭებულა.");
        }
    }

    private void refreshTermuxStatus() {
        if (termuxStatus == null) return;
        String installed = isTermuxInstalled() ? "დაყენებულია" : "ვერ მოიძებნა";
        String permission = checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED ? "მინიჭებულია" : "არ არის მინიჭებული";
        setTermuxStatus("Termux: " + installed + "\nRUN_COMMAND: " + permission);
    }

    private boolean isTermuxInstalled() {
        try { getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0); return true; }
        catch (PackageManager.NameNotFoundException ignored) { return false; }
    }

    private String blockedReason(String command) {
        if (command.length() > MAX_COMMAND_LENGTH) return "ბრძანება ზედმეტად გრძელია.";
        for (Pattern pattern : BLOCKED_COMMANDS) if (pattern.matcher(command).find()) return "Ursafe-მა მაღალი რისკის ბრძანება ამოიცნო და დაბლოკა.";
        return null;
    }

    private String decodeJavascriptString(String value) {
        if (value == null || value.equals("null") || value.equals("undefined")) return "";
        try { return new JSONArray("[" + value + "]").getString(0); }
        catch (Exception ignored) { return value.replaceAll("^\\\"|\\\"$", ""); }
    }

    private void copyToClipboard(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Ursafe Termux result", value));
    }

    private void setTermuxStatus(String value) { if (termuxStatus != null) termuxStatus.setText(value); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD); return view;
    }
    private Button navButton(String label) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(12);
        button.setTextColor(Color.rgb(53, 48, 89)); button.setBackgroundColor(Color.TRANSPARENT); return button;
    }
    private Button compactButton(String label) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(19);
        button.setTextColor(Color.rgb(60, 54, 101)); button.setBackgroundColor(Color.TRANSPARENT); return button;
    }
    private Button primaryButton(String label) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(14);
        button.setTextColor(Color.WHITE); button.setBackground(rounded(Color.rgb(91, 69, 224), 15)); return button;
    }
    private Button darkButton(String label) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(13);
        button.setTextColor(Color.WHITE); button.setBackground(rounded(Color.rgb(62, 66, 82), 15)); return button;
    }
    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
    }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -1, 1f); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private View horizontalSpace() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1)); return v; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safe(String value) { return value == null ? "" : value; }
}
