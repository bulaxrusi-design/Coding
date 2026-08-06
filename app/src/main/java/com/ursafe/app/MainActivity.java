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
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    public static final String ACTION_TERMUX_RESULT = "com.ursafe.app.TERMUX_RESULT";

    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final int REQUEST_TERMUX_PERMISSION = 501;
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger(2000);

    private LinearLayout messages;
    private ScrollView chatScroll;
    private EditText composer;
    private TextView termuxStatus;
    private boolean receiverRegistered;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String kind = value(intent.getStringExtra("request_kind"));
            String message = value(intent.getStringExtra("message"));

            if ("chat".equals(kind)) {
                if ("__URSAFE_SETUP_REQUIRED__".equals(message.trim())) {
                    addAssistantMessage("Termux bridge ჯერ მომზადებული არ არის. ზედა ზოლში დააჭირე „Bridge setup“-ს.");
                } else {
                    addAssistantMessage(message.isEmpty() ? "Termux-იდან ცარიელი პასუხი მივიღე." : message);
                }
            } else if ("setup".equals(kind)) {
                if (message.contains("URSAFE_BRIDGE_READY")) {
                    addAssistantMessage("Termux bridge მზადაა. დაწერე /status ან ჩვეულებრივი ტექსტი.");
                } else {
                    addSystemMessage(message);
                }
            } else {
                addSystemMessage(message);
            }
            refreshTermuxStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        setContentView(createScreen());

        addAssistantMessage(
                "მოგესალმები Ursafe Chat-ში.\n\n" +
                "ეს უკვე native Android ჩატია — WebView აღარ გამოიყენება. " +
                "Termux bridge რეალურად აბრუნებს პასუხს ამავე ჩატში. " +
                "AI ძრავი ჯერ არ არის დაკავშირებული, ამიტომ აპი თავს ChatGPT-ად არ გაასაღებს.");
        addSystemMessage("სცადე: /status");
        refreshTermuxStatus();
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

    private void configureSystemBars() {
        getWindow().setStatusBarColor(Color.rgb(247, 248, 252));
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private View createScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 248, 252));
        root.addView(createToolbar(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        chatScroll.setClipToPadding(false);
        chatScroll.setPadding(dp(14), dp(12), dp(14), dp(16));

        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setGravity(Gravity.BOTTOM);
        chatScroll.addView(messages, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(chatScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(createComposer(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private View createToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), dp(8), dp(12), dp(8));
        toolbar.setBackgroundColor(Color.WHITE);
        toolbar.setElevation(dp(3));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_ursafe_logo);
        logo.setContentDescription("Ursafe");
        toolbar.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(11), 0, 0, 0);
        titles.addView(text("Ursafe Chat", 18, true, Color.rgb(24, 26, 35)), matchWrap());
        titles.addView(text("Native chat + Termux bridge", 12, false, Color.rgb(114, 118, 133)), matchWrap());
        toolbar.addView(titles, weighted());

        termuxStatus = text("Termux", 11, true, Color.rgb(91, 69, 224));
        termuxStatus.setGravity(Gravity.CENTER);
        termuxStatus.setPadding(dp(10), dp(7), dp(10), dp(7));
        termuxStatus.setBackground(rounded(Color.rgb(237, 233, 255), 999));
        termuxStatus.setOnClickListener(v -> showBridgeActions());
        toolbar.addView(termuxStatus, wrapWrap());
        return toolbar;
    }

    private View createComposer() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setPadding(dp(12), dp(8), dp(12), dp(12));
        area.setBackgroundColor(Color.WHITE);
        area.setElevation(dp(8));

        LinearLayout bridgeRow = new LinearLayout(this);
        bridgeRow.setGravity(Gravity.CENTER_VERTICAL);

        Button setup = smallButton("Bridge setup");
        setup.setOnClickListener(v -> prepareBridge());
        bridgeRow.addView(setup, wrapWrap());

        Button test = smallButton("Test");
        LinearLayout.LayoutParams testParams = wrapWrap();
        testParams.setMargins(dp(8), 0, 0, 0);
        test.setOnClickListener(v -> runTermuxTest());
        bridgeRow.addView(test, testParams);

        TextView note = text("AI backend: disconnected", 11, false, Color.rgb(131, 135, 150));
        note.setGravity(Gravity.END);
        bridgeRow.addView(note, weighted());
        area.addView(bridgeRow, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(0, dp(8), 0, 0);

        composer = new EditText(this);
        composer.setHint("მიწერე Ursafe-ს…");
        composer.setTextSize(16);
        composer.setTextColor(Color.rgb(28, 30, 39));
        composer.setHintTextColor(Color.rgb(145, 148, 160));
        composer.setPadding(dp(16), dp(11), dp(16), dp(11));
        composer.setMinHeight(dp(48));
        composer.setMaxLines(4);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        composer.setImeOptions(EditorInfo.IME_ACTION_SEND);
        composer.setBackground(rounded(Color.rgb(244, 245, 249), 22));
        composer.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage();
                return true;
            }
            return false;
        });
        row.addView(composer, weighted());

        TextView send = text("➤", 21, true, Color.WHITE);
        send.setGravity(Gravity.CENTER);
        send.setContentDescription("გაგზავნა");
        send.setBackground(rounded(Color.rgb(91, 69, 224), 22));
        send.setOnClickListener(v -> sendCurrentMessage());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(50), dp(50));
        sendParams.setMargins(dp(9), 0, 0, 0);
        row.addView(send, sendParams);
        area.addView(row, matchWrap());
        return area;
    }

    private void sendCurrentMessage() {
        String prompt = composer.getText().toString().trim();
        if (prompt.isEmpty()) return;
        composer.setText("");
        hideKeyboard();
        addUserMessage(prompt);

        if (!isTermuxInstalled()) {
            addAssistantMessage("Termux ვერ მოიძებნა. ჯერ დააყენე ოფიციალური Termux.");
            return;
        }
        if (!hasTermuxPermission()) {
            addAssistantMessage("Termux-ის RUN_COMMAND ნებართვა ჯერ არ არის მინიჭებული. ნებართვის ფანჯარა ახლა გაიხსნება.");
            requestTermuxPermission();
            return;
        }

        String encoded = Base64.encodeToString(prompt.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String command = "if [ -x \"$HOME/.ursafe/agent.sh\" ]; then \"$HOME/.ursafe/agent.sh\" '" + encoded + "'; else printf '__URSAFE_SETUP_REQUIRED__'; fi";
        runTermuxCommand(command, "chat", "Ursafe chat message");
        addSystemMessage("Termux პასუხს ამუშავებს…");
    }

    private void prepareBridge() {
        if (!ensureTermuxReady()) return;

        String script =
                "mkdir -p \"$HOME/.ursafe\" && " +
                "cat > \"$HOME/.ursafe/agent.sh\" <<'URSAFE_EOF'\n" +
                "#!/data/data/com.termux/files/usr/bin/bash\n" +
                "set -u\n" +
                "payload=\"${1:-}\"\n" +
                "message=\"$(printf '%s' \"$payload\" | base64 -d 2>/dev/null || true)\"\n" +
                "case \"$message\" in\n" +
                "  /status)\n" +
                "    printf 'Termux bridge: online\\n'\n" +
                "    printf 'Shell: %s\\n' \"$SHELL\"\n" +
                "    printf 'Home: %s\\n' \"$HOME\"\n" +
                "    printf 'Time: %s\\n' \"$(date '+%Y-%m-%d %H:%M:%S')\"\n" +
                "    ;;\n" +
                "  /help)\n" +
                "    printf 'Available now: /status, /help\\n'\n" +
                "    printf 'AI engine is not connected yet.\\n'\n" +
                "    ;;\n" +
                "  *)\n" +
                "    printf 'Ursafe received your message through Termux:\\n%s\\n\\n' \"$message\"\n" +
                "    printf 'Native chat and the Termux round-trip work. AI engine is not connected yet.\\n'\n" +
                "    ;;\n" +
                "esac\n" +
                "URSAFE_EOF\n" +
                "chmod 700 \"$HOME/.ursafe/agent.sh\" && printf 'URSAFE_BRIDGE_READY'";
        runTermuxCommand(script, "setup", "Prepare Ursafe bridge");
        addSystemMessage("Termux bridge მზადდება…");
    }

    private void runTermuxTest() {
        if (!ensureTermuxReady()) return;
        runTermuxCommand("printf 'Ursafe ↔ Termux OK\\n'", "test", "Ursafe connection test");
        addSystemMessage("კავშირის ტესტი გაიგზავნა…");
    }

    private boolean ensureTermuxReady() {
        if (!isTermuxInstalled()) {
            addAssistantMessage("Termux ვერ მოიძებნა.");
            return false;
        }
        if (!hasTermuxPermission()) {
            requestTermuxPermission();
            return false;
        }
        return true;
    }

    private void runTermuxCommand(String shellCommand, String kind, String label) {
        int requestId = REQUEST_IDS.incrementAndGet();
        Intent resultIntent = new Intent(this, CommandResultService.class)
                .putExtra("request_id", requestId)
                .putExtra("request_kind", kind);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pendingIntent = PendingIntent.getService(this, requestId, resultIntent, flags);

        Intent command = new Intent();
        command.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
        command.setAction("com.termux.RUN_COMMAND");
        command.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        command.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", shellCommand});
        command.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        command.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label);
        command.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "User-initiated Ursafe bridge operation.");
        command.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent);

        try {
            startService(command);
        } catch (SecurityException error) {
            addAssistantMessage("Termux ნებართვის შეცდომა: " + value(error.getMessage()));
        } catch (Exception error) {
            addAssistantMessage("Termux-თან დაკავშირება ვერ მოხერხდა: " + value(error.getMessage()));
        }
    }

    private void requestTermuxPermission() {
        if (!isTermuxInstalled()) {
            addAssistantMessage("Termux ვერ მოიძებნა.");
            return;
        }
        if (hasTermuxPermission()) {
            addSystemMessage("RUN_COMMAND ნებართვა უკვე მინიჭებულია.");
            return;
        }
        requestPermissions(new String[]{TERMUX_PERMISSION}, REQUEST_TERMUX_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_TERMUX_PERMISSION) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        addSystemMessage(granted ? "Termux RUN_COMMAND ნებართვა მინიჭებულია." : "ნებართვა არ მინიჭებულა. გახსენი Ursafe-ის სისტემური პარამეტრები.");
        refreshTermuxStatus();
    }

    private void showBridgeActions() {
        if (!isTermuxInstalled()) {
            addAssistantMessage("Termux არ არის დაყენებული.");
        } else if (!hasTermuxPermission()) {
            requestTermuxPermission();
        } else {
            Toast.makeText(this, "Termux დაკავშირებულია. Bridge setup-ით მოამზადე ჩატის აგენტი.", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshTermuxStatus() {
        if (termuxStatus == null) return;
        if (!isTermuxInstalled()) {
            termuxStatus.setText("Termux: off");
            termuxStatus.setTextColor(Color.rgb(173, 67, 67));
            termuxStatus.setBackground(rounded(Color.rgb(255, 235, 235), 999));
        } else if (!hasTermuxPermission()) {
            termuxStatus.setText("Permission");
            termuxStatus.setTextColor(Color.rgb(147, 98, 19));
            termuxStatus.setBackground(rounded(Color.rgb(255, 244, 218), 999));
        } else {
            termuxStatus.setText("Termux: on");
            termuxStatus.setTextColor(Color.rgb(45, 128, 83));
            termuxStatus.setBackground(rounded(Color.rgb(226, 247, 235), 999));
        }
    }

    private boolean hasTermuxPermission() {
        return checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void addUserMessage(String value) { addBubble(value, true, false); }
    private void addAssistantMessage(String value) { addBubble(value, false, false); }
    private void addSystemMessage(String value) { addBubble(value, false, true); }

    private void addBubble(String value, boolean user, boolean system) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(user ? Gravity.END : Gravity.START);
        line.setPadding(0, dp(5), 0, dp(5));

        TextView bubble = text(value, system ? 12 : 15, false,
                user ? Color.WHITE : (system ? Color.rgb(111, 115, 130) : Color.rgb(35, 37, 47)));
        bubble.setLineSpacing(dp(2), 1.08f);
        bubble.setPadding(dp(system ? 12 : 15), dp(system ? 8 : 11), dp(system ? 12 : 15), dp(system ? 8 : 11));
        bubble.setBackground(rounded(user ? Color.rgb(91, 69, 224) : (system ? Color.rgb(234, 236, 242) : Color.WHITE), system ? 14 : 20));
        if (!system) bubble.setElevation(dp(1));
        bubble.setMaxWidth(Math.min(getResources().getDisplayMetrics().widthPixels - dp(70), dp(420)));

        line.addView(bubble, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        messages.addView(line, matchWrap());
        scrollToBottom();
    }

    private void scrollToBottom() {
        if (chatScroll != null) chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void hideKeyboard() {
        View current = getCurrentFocus();
        if (current == null) return;
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(current.getWindowToken(), 0);
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTextColor(Color.rgb(70, 58, 135));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(12), dp(7), dp(12), dp(7));
        button.setBackground(rounded(Color.rgb(239, 236, 255), 999));
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static String value(String input) { return input == null ? "" : input; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
