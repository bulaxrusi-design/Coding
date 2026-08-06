package com.ursafe.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class TermuxRunner {
    private static final AtomicInteger IDS = new AtomicInteger(7000);
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String HOME = "/data/data/com.termux/files/home";

    private TermuxRunner() {}

    public static void runRemoteJob(Context context, String jobId, JSONObject job) throws Exception {
        ensureReady(context);
        String command = job.getString("command");
        String workdir = job.optString("workdir", HOME);
        JSONArray artifacts = job.optJSONArray("artifacts");
        String wrapper = buildWrapper(context, jobId, command, workdir, artifacts);
        int requestId = IDS.incrementAndGet();
        Intent resultIntent = new Intent(context, BridgeResultService.class)
                .putExtra("request_id", requestId)
                .putExtra("request_kind", "bridge_remote")
                .putExtra("job_id", jobId)
                .putExtra("command", command)
                .putExtra("job_json", job.toString());
        PendingIntent pendingIntent = PendingIntent.getService(context, requestId, resultIntent, PendingIntent.FLAG_ONE_SHOT | mutableFlag());
        startTermux(context, wrapper, HOME, "Ursafe remote job", "Runs a command explicitly approved in Ursafe.", pendingIntent);
    }

    public static void uploadEncryptedResult(Context context, String jobId, JSONObject encryptedEnvelope) throws Exception {
        ensureReady(context);
        String deviceId = BridgeCrypto.getOrCreateDeviceId(context);
        String remotePath = "ursafe-bridge/results/" + safePath(deviceId) + "/" + safePath(jobId) + ".json";
        String envelopeBase64 = Base64.encodeToString(encryptedEnvelope.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String script = "set -eu\n"
                + "command -v gh >/dev/null 2>&1 || { echo 'gh is not installed' >&2; exit 127; }\n"
                + "command -v python >/dev/null 2>&1 || { echo 'python is not installed' >&2; exit 127; }\n"
                + "tmp=\"$(mktemp)\"\n"
                + "python - \"$tmp\" " + shellQuote(envelopeBase64) + " <<'PY'\n"
                + "import json,sys\n"
                + "out,encoded=sys.argv[1:]\n"
                + "payload={'message':'Ursafe encrypted result " + safePython(jobId) + "','content':encoded,'branch':'" + BridgeConfig.BRANCH + "'}\n"
                + "with open(out,'w',encoding='utf-8') as f: json.dump(payload,f)\n"
                + "PY\n"
                + "gh api --method PUT " + shellQuote("repos/" + BridgeConfig.REPOSITORY + "/contents/" + remotePath) + " --input \"$tmp\" >/dev/null\n"
                + "rm -f \"$tmp\"\n";
        int requestId = IDS.incrementAndGet();
        Intent uploadResult = new Intent(context, BridgeUploadResultService.class).putExtra("job_id", jobId);
        PendingIntent pendingIntent = PendingIntent.getService(context, requestId, uploadResult, PendingIntent.FLAG_ONE_SHOT | mutableFlag());
        startTermux(context, script, HOME, "Ursafe result upload", "Uploads an encrypted command result to the paired GitHub bridge.", pendingIntent);
    }

    private static String buildWrapper(Context context, String jobId, String command, String workdir, JSONArray artifacts) {
        String commandBase64 = Base64.encodeToString(command.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        StringBuilder script = new StringBuilder();
        script.append("set +e\n");
        script.append("ursafe_cmd=\"$(mktemp)\"\n");
        script.append("printf %s ").append(shellQuote(commandBase64)).append(" | base64 -d > \"$ursafe_cmd\"\n");
        script.append("(cd ").append(shellQuote(workdir)).append(" && bash \"$ursafe_cmd\")\n");
        script.append("ursafe_exit=$?\n");
        script.append("rm -f \"$ursafe_cmd\"\n");
        if (artifacts != null && artifacts.length() > 0) {
            script.append(artifactUploadFunction());
            String deviceId = BridgeCrypto.getOrCreateDeviceId(context);
            for (int i = 0; i < artifacts.length(); i++) {
                String path = artifacts.optString(i, "").trim();
                if (path.isEmpty()) continue;
                String filename = fileName(path);
                String remotePath = "ursafe-bridge/artifacts/" + safePath(deviceId) + "/" + safePath(jobId) + "/" + String.format(Locale.ROOT, "%02d-", i + 1) + safePath(filename);
                script.append("ursafe_upload_artifact ").append(shellQuote(path)).append(" ").append(shellQuote(remotePath)).append("\n");
            }
        }
        script.append("exit \"$ursafe_exit\"\n");
        return script.toString();
    }

    private static String artifactUploadFunction() {
        return "ursafe_upload_artifact() {\n"
                + "  src=\"$1\"; remote=\"$2\"\n"
                + "  [ -f \"$src\" ] || return 0\n"
                + "  command -v gh >/dev/null 2>&1 || return 0\n"
                + "  command -v python >/dev/null 2>&1 || return 0\n"
                + "  payload=\"$(mktemp)\"\n"
                + "  python - \"$src\" \"$payload\" <<'PY'\n"
                + "import base64,json,os,sys\n"
                + "src,out=sys.argv[1:]\n"
                + "size=os.path.getsize(src)\n"
                + "if size>50*1024*1024: raise SystemExit('artifact exceeds 50 MiB')\n"
                + "with open(src,'rb') as f: content=base64.b64encode(f.read()).decode('ascii')\n"
                + "with open(out,'w',encoding='utf-8') as f: json.dump({'message':'Ursafe artifact '+os.path.basename(src),'content':content,'branch':'" + BridgeConfig.BRANCH + "'},f)\n"
                + "PY\n"
                + "  gh api --method PUT \"repos/" + BridgeConfig.REPOSITORY + "/contents/$remote\" --input \"$payload\" >/dev/null 2>&1 || true\n"
                + "  rm -f \"$payload\"\n"
                + "}\n";
    }

    private static void startTermux(Context context, String script, String workdir, String label, String description, PendingIntent pendingIntent) {
        Intent command = new Intent();
        command.setClassName(BridgeConfig.TERMUX_PACKAGE, "com.termux.app.RunCommandService");
        command.setAction("com.termux.RUN_COMMAND");
        command.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        command.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", script});
        command.putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir);
        command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        command.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label);
        command.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", description);
        command.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent);
        context.startService(command);
    }

    private static void ensureReady(Context context) {
        if (context.checkSelfPermission(BridgeConfig.TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Termux RUN_COMMAND permission is missing");
        try { context.getPackageManager().getPackageInfo(BridgeConfig.TERMUX_PACKAGE, 0); }
        catch (PackageManager.NameNotFoundException error) { throw new IllegalStateException("Termux is not installed"); }
    }

    private static int mutableFlag() { return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0; }
    private static String shellQuote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    private static String safePath(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }
    private static String fileName(String path) { int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')); return slash >= 0 ? path.substring(slash + 1) : path; }
    private static String safePython(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'"); }
}
