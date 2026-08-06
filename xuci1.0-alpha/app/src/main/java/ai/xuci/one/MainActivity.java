package ai.xuci.one;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * xuci1.0 Local Bridge.
 * The APK connects to a llama.cpp-compatible server running on the same phone.
 * GGUF weights remain outside the APK, so model size is limited by phone storage.
 */
public final class MainActivity extends Activity {
    private static final int PICK_FILES = 41;
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final int TEXT_ATTACHMENT_LIMIT = 384 * 1024;

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final List<WorkspaceFile> attachments = Collections.synchronizedList(new ArrayList<>());

    private TextView transcript;
    private TextView status;
    private EditText input;
    private ProgressBar progress;
    private ScrollView scroll;
    private SharedPreferences prefs;
    private MemoryDb memory;
    private File importsDir;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("xuci_local_settings", MODE_PRIVATE);
        memory = new MemoryDb(this);
        File storageRoot = getExternalFilesDir(null);
        if (storageRoot == null) storageRoot = getFilesDir();
        importsDir = new File(storageRoot, "xuci-workspace/imports");
        if (!importsDir.exists() && !importsDir.mkdirs()) {
            Toast.makeText(this, "Workspace საქაღალდე ვერ შეიქმნა", Toast.LENGTH_LONG).show();
        }
        buildUi();
        restoreRecentMessages();
        if (transcript.length() == 0) {
            appendAssistant("xuci1.0 Local Bridge ჩაირთო. ფასიანი API არ მჭირდება. " +
                    "მე ვუკავშირდები ამავე ტელეფონზე გაშვებულ llama-server-ს. " +
                    "ჯერ გახსენი „ძრავა“, ნახე Setup და შემდეგ დააჭირე „ტესტი“.\n\n" +
                    "შეგიძლია დაურთო APK, ZIP, source ან ტექსტური ფაილი. დიდი ფაილები streaming-ით ინახება.");
        }
        refreshStatus();
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.rgb(14, 16, 22));
        getWindow().setNavigationBarColor(Color.rgb(14, 16, 22));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.setBackgroundColor(Color.rgb(14, 16, 22));

        TextView title = text("xuci1.0", 24, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        root.addView(text("ქართული Local Coding Agent • Bridge 0.3", 12, Color.LTGRAY));

        status = text("", 12, Color.rgb(128, 210, 255));
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        transcript = text("", 15, Color.WHITE);
        transcript.setTextIsSelectable(true);
        transcript.setPadding(dp(10), dp(10), dp(10), dp(10));
        transcript.setBackgroundColor(Color.rgb(27, 30, 39));
        scroll = new ScrollView(this);
        scroll.addView(transcript, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)));

        input = new EditText(this);
        input.setHint("მომეცი Android / Python / APK ამოცანა…");
        input.setHintTextColor(Color.GRAY);
        input.setTextColor(Color.WHITE);
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setBackgroundColor(Color.rgb(34, 38, 48));
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        firstRow.setGravity(Gravity.CENTER_VERTICAL);
        Button attach = button("+ ფაილი");
        Button engine = button("ძრავა");
        Button test = button("ტესტი");
        firstRow.addView(attach, new LinearLayout.LayoutParams(0, dp(46), 1f));
        firstRow.addView(engine, new LinearLayout.LayoutParams(0, dp(46), 1f));
        firstRow.addView(test, new LinearLayout.LayoutParams(0, dp(46), 1f));
        root.addView(firstRow);

        Button send = button("გაგზავნა");
        root.addView(send, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        attach.setOnClickListener(v -> pickFiles());
        engine.setOnClickListener(v -> showEngineSettings());
        test.setOnClickListener(v -> testEngine());
        send.setOnClickListener(v -> sendPrompt());
        setContentView(root);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_FILES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) return;
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) importUri(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            importUri(data.getData());
        }
    }

    private void importUri(Uri uri) {
        setBusy(true, "ფაილის streaming იმპორტი…");
        io.execute(() -> {
            try {
                WorkspaceFile file = copyUri(uri);
                attachments.add(file);
                runOnUiThread(() -> {
                    appendTool("დაერთო: " + file.name + " • " + formatBytes(file.size) +
                            " • SHA-256 " + file.sha256.substring(0, 16) + "…");
                    setBusy(false, attachments.size() + " ფაილი მზადაა");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendTool("იმპორტის შეცდომა: " + safeMessage(e));
                    setBusy(false, "იმპორტი ვერ დასრულდა");
                });
            }
        });
    }

    private WorkspaceFile copyUri(Uri uri) throws Exception {
        String name = "file-" + System.currentTimeMillis();
        long announcedSize = -1;
        Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) announcedSize = cursor.getLong(sizeColumn);
                }
            } finally {
                cursor.close();
            }
        }
        File target = uniqueFile(importsDir, safeFileName(name));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied = 0;
        InputStream raw = getContentResolver().openInputStream(uri);
        if (raw == null) throw new IllegalStateException("ფაილი ვერ გაიხსნა");
        try (InputStream in = new BufferedInputStream(raw, BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            int lastPercent = -1;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                copied += read;
                if (announcedSize > 0) {
                    int percent = (int) Math.min(100, copied * 100 / announcedSize);
                    if (percent / 10 != lastPercent / 10) {
                        lastPercent = percent;
                        int shown = percent;
                        runOnUiThread(() -> status.setText("იმპორტი: " + shown + "%"));
                    }
                }
            }
        }
        return new WorkspaceFile(name, target, copied, hex(digest.digest()), getContentResolver().getType(uri));
    }

    private void sendPrompt() {
        String prompt = input.getText().toString().trim();
        if (prompt.isEmpty()) return;
        input.setText("");
        appendUser(prompt);
        memory.saveMessage("USER", prompt);
        setBusy(true, "Local agent მუშაობს…");

        List<WorkspaceFile> snapshot;
        synchronized (attachments) {
            snapshot = new ArrayList<>(attachments);
            attachments.clear();
        }
        io.execute(() -> process(prompt, snapshot));
    }

    private void process(String prompt, List<WorkspaceFile> files) {
        Agent agent = route(prompt, files);
        try {
            StringBuilder evidence = new StringBuilder();
            List<String> events = new ArrayList<>();
            for (WorkspaceFile file : files) {
                if (file.name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    ApkInventory inv = inspectApk(file.file);
                    evidence.append("\nAPK inventory: ").append(file.name).append('\n')
                            .append("entries=").append(inv.entries)
                            .append(", dex=").append(inv.dex)
                            .append(", nativeSo=").append(inv.nativeSo)
                            .append(", signatures=").append(inv.signatures)
                            .append(", manifest=").append(inv.hasManifest)
                            .append(", resources=").append(inv.hasResources).append('\n');
                    events.add("APK inventory");
                }
                if (looksTextual(file) && file.size <= TEXT_ATTACHMENT_LIMIT) {
                    evidence.append("\nFILE: ").append(file.name).append("\n")
                            .append(readFileLimited(file.file, TEXT_ATTACHMENT_LIMIT)).append('\n');
                    events.add("text attachment read");
                } else {
                    evidence.append("\nFILE metadata: ").append(file.name)
                            .append(", size=").append(file.size)
                            .append(", sha256=").append(file.sha256).append('\n');
                }
            }

            String recalled = memory.recall(prompt);
            EngineConfig config = loadEngineConfig();
            String system = "შენ ხარ xuci1.0-ის " + agent.name() + " აგენტი. " +
                    "უპასუხე ქართულად, ხოლო code identifiers და commands დატოვე ორიგინალ ენაზე. " +
                    "შენი სპეციალიზაციაა Android, Kotlin/Java, Python, Gradle და ავტორიზებული APK ანალიზი. " +
                    "არ მოიგონო შესრულებული build ან tool result. მიუთითე შეზღუდვები ზუსტად. " +
                    "წინა მეხსიერება: " + (recalled.isEmpty() ? "არ არის" : recalled);
            String user = prompt + attachmentSummary(files) + evidence;
            String answer = callLocal(config, system, user);
            memory.learn(agent.name(), prompt, answer, true);
            String finalAnswer = answer + (events.isEmpty() ? "" : "\n\nინსტრუმენტები: " + String.join("; ", events));
            memory.saveMessage("ASSISTANT", finalAnswer);
            runOnUiThread(() -> {
                appendAssistant("[" + agent.name() + "]\n" + finalAnswer);
                setBusy(false, agent.name() + " დასრულდა");
            });
        } catch (Exception e) {
            memory.learn(agent.name(), prompt, safeMessage(e), false);
            runOnUiThread(() -> {
                appendAssistant("[" + agent.name() + "] ძრავის შეცდომა: " + safeMessage(e) +
                        "\n\nგახსენით „ძრავა“ → „Setup“, გაუშვით llama-server და შემდეგ დააჭირეთ „ტესტი“.");
                setBusy(false, "ძრავა მიუწვდომელია");
            });
        }
    }

    private Agent route(String prompt, List<WorkspaceFile> files) {
        StringBuilder all = new StringBuilder(prompt.toLowerCase(Locale.ROOT));
        for (WorkspaceFile file : files) all.append(' ').append(file.name.toLowerCase(Locale.ROOT));
        String text = all.toString();
        if (containsAny(text, ".apk", ".aab", " apk", "smali", "dex", "manifest", "დეკომპილ")) return Agent.APK;
        if (containsAny(text, "python", ".py", "pip", "პითონ")) return Agent.PYTHON;
        if (containsAny(text, "kotlin", "gradle", "compose", "android", "activity", "ანდროიდ")) return Agent.ANDROID;
        if (containsAny(text, "crash", "exception", "stacktrace", "შეცდომ", "debug")) return Agent.DEBUGGER;
        if (containsAny(text, "http://", "https://", "ინტერნეტ", "მოძებნ", "research")) return Agent.RESEARCH;
        return Agent.ORCHESTRATOR;
    }

    private String callLocal(EngineConfig config, String system, String user) throws Exception {
        String endpoint = normalizeChatEndpoint(config.endpoint);
        validateEndpoint(endpoint);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(config.timeoutSeconds * 1000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "xuci1.0-local/0.3 Android");

        JSONObject body = new JSONObject();
        body.put("model", config.model.isEmpty() ? "local-model" : config.model);
        body.put("temperature", 0.15);
        body.put("max_tokens", config.maxTokens);
        body.put("stream", false);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", system));
        messages.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", messages);

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload);
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String raw = readLimited(stream, 4_000_000);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + trim(raw, 700));
        }
        JSONObject root = new JSONObject(raw);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("ძრავამ choices არ დააბრუნა: " + trim(raw, 500));
        }
        JSONObject first = choices.getJSONObject(0);
        JSONObject message = first.optJSONObject("message");
        if (message != null) return message.optString("content", "");
        return first.optString("text", "");
    }

    private void testEngine() {
        setBusy(true, "Local engine connection test…");
        io.execute(() -> {
            try {
                String answer = callLocal(loadEngineConfig(),
                        "უპასუხე მხოლოდ ერთი მოკლე ქართული წინადადებით.",
                        "დაწერე, რომ xuci local engine მუშაობს.");
                runOnUiThread(() -> {
                    appendTool("ძრავასთან კავშირი წარმატებულია: " + trim(answer, 240));
                    setBusy(false, "LOCAL ENGINE ONLINE");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendTool("კავშირის ტესტი ჩავარდა: " + safeMessage(e));
                    setBusy(false, "LOCAL ENGINE OFFLINE");
                });
            }
        });
    }

    private void showEngineSettings() {
        EngineConfig current = loadEngineConfig();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), 0);

        EditText endpoint = field("Local endpoint", current.endpoint);
        EditText model = field("Model label", current.model);
        EditText maxTokens = field("Max output tokens", String.valueOf(current.maxTokens));
        EditText timeout = field("Timeout seconds", String.valueOf(current.timeoutSeconds));
        form.addView(endpoint);
        form.addView(model);
        form.addView(maxTokens);
        form.addView(timeout);

        new AlertDialog.Builder(this)
                .setTitle("Local GGUF ძრავა")
                .setMessage("ნაგულისხმევია llama.cpp server ამავე ტელეფონზე. API key და ანგარიში საჭირო არ არის.")
                .setView(form)
                .setNeutralButton("Setup", (dialog, which) -> showSetup())
                .setNegativeButton("გაუქმება", null)
                .setPositiveButton("შენახვა", (dialog, which) -> {
                    int tokens = parseInt(maxTokens.getText().toString(), 1024, 64, 8192);
                    int seconds = parseInt(timeout.getText().toString(), 180, 10, 900);
                    prefs.edit()
                            .putString("endpoint", endpoint.getText().toString().trim())
                            .putString("model", model.getText().toString().trim())
                            .putInt("max_tokens", tokens)
                            .putInt("timeout_seconds", seconds)
                            .apply();
                    refreshStatus();
                })
                .show();
    }

    private void showSetup() {
        TextView guide = text(
                "1. დააყენე Termux F-Droid-იდან.\n\n" +
                "2. Termux-ში ააწყვე ან დააყენე llama.cpp და llama-server.\n\n" +
                "3. GGUF მოდელი შეინახე ტელეფონში, მაგალითად:\n" +
                "/sdcard/Download/model.gguf\n\n" +
                "4. გაუშვი server მხოლოდ localhost-ზე:\n" +
                "llama-server -m /sdcard/Download/model.gguf --host 127.0.0.1 --port 8080 -c 2048 -t 6\n\n" +
                "5. xuci-ში endpoint დატოვე:\n" +
                "http://127.0.0.1:8080/v1\n\n" +
                "6. დააჭირე „ტესტი“.\n\n" +
                "A70-ზე დაიწყე მცირე 0.5B–1.5B Q4 GGUF მოდელით. დიდმა მოდელმა შეიძლება RAM ამოწუროს.",
                14, Color.WHITE);
        guide.setTextIsSelectable(true);
        guide.setPadding(dp(18), dp(12), dp(18), dp(12));
        ScrollView view = new ScrollView(this);
        view.addView(guide);
        new AlertDialog.Builder(this)
                .setTitle("Local engine setup")
                .setView(view)
                .setPositiveButton("გასაგებია", null)
                .show();
    }

    private EngineConfig loadEngineConfig() {
        return new EngineConfig(
                prefs.getString("endpoint", "http://127.0.0.1:8080/v1"),
                prefs.getString("model", "local-model"),
                prefs.getInt("max_tokens", 1024),
                prefs.getInt("timeout_seconds", 180)
        );
    }

    private void refreshStatus() {
        EngineConfig config = loadEngineConfig();
        status.setText("LOCAL BRIDGE • " + config.endpoint + " • files: streaming");
    }

    private EditText field(String hint, String value) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setText(value);
        edit.setSingleLine(true);
        return edit;
    }

    private String normalizeChatEndpoint(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("/+$", "");
        if (value.isEmpty()) value = "http://127.0.0.1:8080/v1";
        if (value.endsWith("/chat/completions")) return value;
        if (value.endsWith("/v1")) return value + "/chat/completions";
        return value + "/v1/chat/completions";
    }

    private void validateEndpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
        if ("http".equals(scheme) && !loopback) {
            throw new IllegalArgumentException("HTTP დაშვებულია მხოლოდ localhost-ზე; სხვა მოწყობილობისთვის გამოიყენე HTTPS");
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Endpoint უნდა იყოს HTTP localhost ან HTTPS");
        }
    }

    private ApkInventory inspectApk(File file) throws Exception {
        int entries = 0;
        int dex = 0;
        int nativeSo = 0;
        int signatures = 0;
        boolean manifest = false;
        boolean resources = false;
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                String name = enumeration.nextElement().getName();
                entries++;
                if (name.matches("classes(\\d*)?\\.dex")) dex++;
                if (name.startsWith("lib/") && name.endsWith(".so")) nativeSo++;
                if (name.startsWith("META-INF/") &&
                        (name.endsWith(".RSA") || name.endsWith(".DSA") ||
                                name.endsWith(".EC") || name.endsWith(".SF"))) signatures++;
                if ("AndroidManifest.xml".equals(name)) manifest = true;
                if ("resources.arsc".equals(name)) resources = true;
            }
        }
        return new ApkInventory(entries, dex, nativeSo, signatures, manifest, resources);
    }

    private boolean looksTextual(WorkspaceFile file) {
        String name = file.name.toLowerCase(Locale.ROOT);
        if (file.mime != null && (file.mime.startsWith("text/") || file.mime.contains("json") || file.mime.contains("xml"))) return true;
        return containsAny(name, ".txt", ".md", ".kt", ".java", ".py", ".js", ".ts", ".json", ".xml", ".gradle", ".properties", ".yaml", ".yml", ".log", ".smali");
    }

    private String readFileLimited(File file, int limit) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return readLimited(in, limit);
        }
    }

    private String attachmentSummary(List<WorkspaceFile> files) {
        if (files.isEmpty()) return "";
        StringBuilder builder = new StringBuilder("\n\nAttachments:\n");
        for (WorkspaceFile file : files) {
            builder.append("- ").append(file.name).append(", ")
                    .append(file.size).append(" bytes, sha256=").append(file.sha256).append('\n');
        }
        return builder.toString();
    }

    private void restoreRecentMessages() {
        for (SavedMessage message : memory.recentMessages(12)) {
            if ("USER".equals(message.role)) appendUser(message.text);
            else appendAssistant(message.text);
        }
    }

    private void appendUser(String value) {
        append("\nშენ\n" + value + "\n");
    }

    private void appendAssistant(String value) {
        append("\nxuci\n" + value + "\n");
    }

    private void appendTool(String value) {
        append("\nTOOL • " + value + "\n");
    }

    private void append(String value) {
        transcript.append(value);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setBusy(boolean busy, String message) {
        runOnUiThread(() -> {
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            status.setText(message);
        });
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private static String readLimited(InputStream input, int limit) throws Exception {
        if (input == null) return "";
        try (InputStream in = new BufferedInputStream(input);
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 64 * 1024))) {
            byte[] buffer = new byte[16 * 1024];
            int remaining = limit;
            while (remaining > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static File uniqueFile(File parent, String name) {
        File candidate = new File(parent, name);
        int index = 1;
        String base = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }
        while (candidate.exists()) candidate = new File(parent, base + "-" + index++ + extension);
        return candidate;
    }

    private static String safeFileName(String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) safe = "file-" + System.currentTimeMillis();
        return safe.length() > 180 ? safe.substring(0, 180) : safe;
    }

    private static String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) builder.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return builder.toString();
    }

    private static String formatBytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", value / 1024.0);
        if (value < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", value / 1024.0 / 1024.0);
        return String.format(Locale.ROOT, "%.2f GB", value / 1024.0 / 1024.0 / 1024.0);
    }

    private static int parseInt(String value, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    private static String trim(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        memory.close();
        super.onDestroy();
    }

    private enum Agent { ORCHESTRATOR, ANDROID, PYTHON, APK, RESEARCH, DEBUGGER }

    private static final class WorkspaceFile {
        final String name;
        final File file;
        final long size;
        final String sha256;
        final String mime;

        WorkspaceFile(String name, File file, long size, String sha256, String mime) {
            this.name = name;
            this.file = file;
            this.size = size;
            this.sha256 = sha256;
            this.mime = mime;
        }
    }

    private static final class ApkInventory {
        final int entries;
        final int dex;
        final int nativeSo;
        final int signatures;
        final boolean hasManifest;
        final boolean hasResources;

        ApkInventory(int entries, int dex, int nativeSo, int signatures,
                     boolean hasManifest, boolean hasResources) {
            this.entries = entries;
            this.dex = dex;
            this.nativeSo = nativeSo;
            this.signatures = signatures;
            this.hasManifest = hasManifest;
            this.hasResources = hasResources;
        }
    }

    private static final class EngineConfig {
        final String endpoint;
        final String model;
        final int maxTokens;
        final int timeoutSeconds;

        EngineConfig(String endpoint, String model, int maxTokens, int timeoutSeconds) {
            this.endpoint = endpoint == null ? "" : endpoint;
            this.model = model == null ? "" : model;
            this.maxTokens = maxTokens;
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    private static final class SavedMessage {
        final String role;
        final String text;

        SavedMessage(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    private static final class MemoryDb extends SQLiteOpenHelper {
        MemoryDb(Context context) {
            super(context, "xuci_memory.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, role TEXT NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE lessons(id INTEGER PRIMARY KEY AUTOINCREMENT, agent TEXT NOT NULL, query TEXT NOT NULL, lesson TEXT NOT NULL, score REAL NOT NULL, ts INTEGER NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }

        void saveMessage(String role, String text) {
            ContentValues values = new ContentValues();
            values.put("id", UUID.randomUUID().toString());
            values.put("role", role);
            values.put("text", trim(text, 40_000));
            values.put("ts", System.currentTimeMillis());
            getWritableDatabase().insert("messages", null, values);
        }

        List<SavedMessage> recentMessages(int limit) {
            Cursor cursor = getReadableDatabase().rawQuery(
                    "SELECT role, text FROM messages ORDER BY ts DESC LIMIT ?",
                    new String[]{String.valueOf(limit)});
            List<SavedMessage> reversed = new ArrayList<>();
            try {
                while (cursor.moveToNext()) reversed.add(new SavedMessage(cursor.getString(0), cursor.getString(1)));
            } finally {
                cursor.close();
            }
            Collections.reverse(reversed);
            return reversed;
        }

        void learn(String agent, String query, String answer, boolean verified) {
            ContentValues values = new ContentValues();
            values.put("agent", agent);
            values.put("query", trim(query, 1500));
            values.put("lesson", trim(answer, 6000));
            values.put("score", verified ? 1.5 : 0.4);
            values.put("ts", System.currentTimeMillis());
            getWritableDatabase().insert("lessons", null, values);
        }

        String recall(String query) {
            String[] words = query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_.-]+");
            for (String word : words) {
                if (word.length() < 4) continue;
                Cursor cursor = getReadableDatabase().rawQuery(
                        "SELECT lesson FROM lessons WHERE lower(query || ' ' || lesson) LIKE ? ORDER BY score DESC, ts DESC LIMIT 3",
                        new String[]{"%" + word + "%"});
                try {
                    StringBuilder result = new StringBuilder();
                    while (cursor.moveToNext()) result.append(trim(cursor.getString(0), 800)).append(" | ");
                    return result.toString();
                } finally {
                    cursor.close();
                }
            }
            return "";
        }
    }
}
