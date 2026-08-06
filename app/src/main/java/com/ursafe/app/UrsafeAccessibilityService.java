package com.ursafe.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

public final class UrsafeAccessibilityService extends AccessibilityService {
    private static volatile UrsafeAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onServiceConnected() {
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        String packageName = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (!packageName.isEmpty()) ScreenFrameStore.setForegroundPackage(packageName);
        String text = event.getText() == null ? "" : event.getText().toString();
        QaSessionManager.onAccessibilityEvent(packageName, event.getEventType(), text);
        SmartAdCloser.onEvent(this, event);
    }

    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    public static boolean isReady() { return instance != null; }

    public static boolean tap(float x, float y, long durationMs) {
        UrsafeAccessibilityService service = instance;
        if (service == null) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, Math.max(40, durationMs));
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean accepted = service.dispatchGesture(gesture,
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription description) {
                        QaSessionManager.recordAction("tap_completed", x + "," + y);
                    }
                    @Override public void onCancelled(GestureDescription description) {
                        QaSessionManager.recordAction("tap_cancelled", x + "," + y);
                    }
                }, service.main);
        if (accepted) QaSessionManager.recordAction("tap_accepted", x + "," + y + "," + durationMs);
        return accepted;
    }

    public static boolean swipe(float fromX, float fromY, float toX, float toY, long durationMs) {
        UrsafeAccessibilityService service = instance;
        if (service == null) return false;
        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(toX, toY);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, Math.max(100, durationMs));
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean accepted = service.dispatchGesture(gesture,
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription description) {
                        QaSessionManager.recordAction("swipe_completed",
                                fromX + "," + fromY + "->" + toX + "," + toY);
                    }
                    @Override public void onCancelled(GestureDescription description) {
                        QaSessionManager.recordAction("swipe_cancelled",
                                fromX + "," + fromY + "->" + toX + "," + toY);
                    }
                }, service.main);
        if (accepted) QaSessionManager.recordAction("swipe_accepted",
                fromX + "," + fromY + "->" + toX + "," + toY + "," + durationMs);
        return accepted;
    }

    public static boolean back() {
        UrsafeAccessibilityService service = instance;
        boolean ok = service != null && service.performGlobalAction(GLOBAL_ACTION_BACK);
        if (ok) QaSessionManager.recordAction("back", "");
        return ok;
    }

    public static boolean home() {
        UrsafeAccessibilityService service = instance;
        boolean ok = service != null && service.performGlobalAction(GLOBAL_ACTION_HOME);
        if (ok) QaSessionManager.recordAction("home", "");
        return ok;
    }
}
