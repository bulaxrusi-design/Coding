package com.ursafe.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Locale;

public final class SmartAdCloser {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long SCAN_GAP_MS = 450L;
    private static final long CLICK_GAP_MS = 1600L;
    private static final long FOREIGN_BACK_DELAY_MS = 4200L;
    private static long lastScanMs;
    private static long lastClickMs;
    private static long foreignSinceMs;
    private static String foreignPackage = "";

    private SmartAdCloser() {}

    public static void onEvent(AccessibilityService service, AccessibilityEvent event) {
        if (service == null || event == null) return;
        boolean enabled = LiveControlSession.isActive(service) || NumberMatchAgent.isEnabled();
        if (!enabled) {
            foreignSinceMs = 0L;
            foreignPackage = "";
            return;
        }

        String packageName = event.getPackageName() == null
                ? "" : event.getPackageName().toString();
        String target = LiveControlSession.isActive(service)
                ? LiveControlSession.targetPackage(service) : NumberMatchAgent.targetPackage();
        handleForeignPackage(service, packageName, target);

        long now = System.currentTimeMillis();
        if (now - lastScanMs < SCAN_GAP_MS || now - lastClickMs < CLICK_GAP_MS) return;
        lastScanMs = now;
        if (isProtectedPackage(packageName)) return;

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;
        try {
            AccessibilityNodeInfo candidate = findCloseCandidate(root);
            if (candidate == null) return;
            AccessibilityNodeInfo clickable = clickableParent(candidate);
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastClickMs = now;
                QaSessionManager.recordAction("ad_close", describe(candidate));
            }
        } finally {
            root.recycle();
        }
    }

    private static AccessibilityNodeInfo findCloseCandidate(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        int visited = 0;
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        while (!queue.isEmpty() && visited++ < 300) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String text = normalized(node.getText());
            String description = normalized(node.getContentDescription());
            int score = Math.max(score(text), score(description));
            if (score > bestScore && score > 0 && !isDangerous(text + " " + description)) {
                if (best != null) best.recycle();
                best = AccessibilityNodeInfo.obtain(node);
                bestScore = score;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
            node.recycle();
        }
        while (!queue.isEmpty()) queue.removeFirst().recycle();
        return best;
    }

    private static int score(String value) {
        if (value.isEmpty()) return 0;
        if (value.equals("×") || value.equals("✕") || value.equals("✖")
                || value.equals("x") || value.equals("close ad")) return 100;
        if (value.equals("skip ad") || value.equals("skip")
                || value.equals("dismiss") || value.equals("close")
                || value.equals("no thanks") || value.equals("not now")
                || value.equals("later") || value.equals("გამოტოვება")
                || value.equals("დახურვა") || value.equals("არა, მადლობა")) return 90;
        if (value.contains("skip ad") || value.contains("close ad")
                || value.contains("dismiss ad") || value.contains("no thanks")) return 75;
        return 0;
    }

    private static boolean isDangerous(String value) {
        String text = normalized(value);
        return text.contains("close all") || text.contains("force stop")
                || text.contains("delete") || text.contains("uninstall")
                || text.contains("purchase") || text.contains("subscribe")
                || text.contains("pay") || text.contains("buy");
    }

    private static AccessibilityNodeInfo clickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (current.isClickable() && current.isEnabled() && current.isVisibleToUser()) {
                return current;
            }
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        if (current != null) current.recycle();
        return null;
    }

    private static void handleForeignPackage(AccessibilityService service,
                                             String packageName, String target) {
        if (target == null || target.isEmpty() || packageName == null
                || packageName.isEmpty() || target.equals(packageName)
                || isProtectedPackage(packageName)) {
            foreignSinceMs = 0L;
            foreignPackage = "";
            return;
        }
        long now = System.currentTimeMillis();
        if (!packageName.equals(foreignPackage)) {
            foreignPackage = packageName;
            foreignSinceMs = now;
            return;
        }
        if (now - foreignSinceMs < FOREIGN_BACK_DELAY_MS
                || now - lastClickMs < CLICK_GAP_MS) return;
        lastClickMs = now;
        foreignSinceMs = now;
        MAIN.post(() -> {
            if (UrsafeAccessibilityService.back()) {
                QaSessionManager.recordAction("ad_back", packageName);
            }
        });
    }

    private static boolean isProtectedPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return true;
        return packageName.equals("com.ursafe.app")
                || packageName.equals("com.android.systemui")
                || packageName.contains("permissioncontroller")
                || packageName.contains("settings")
                || packageName.contains("launcher")
                || packageName.contains("inputmethod");
    }

    private static String describe(AccessibilityNodeInfo node) {
        return normalized(node.getText()) + "|" + normalized(node.getContentDescription());
    }

    private static String normalized(CharSequence value) {
        if (value == null) return "";
        return value.toString().trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }
}
