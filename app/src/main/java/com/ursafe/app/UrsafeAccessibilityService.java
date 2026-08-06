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
        if (event != null && event.getPackageName() != null) {
            ScreenFrameStore.setForegroundPackage(event.getPackageName().toString());
        }
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
        return service.dispatchGesture(gesture, null, service.main);
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
        return service.dispatchGesture(gesture, null, service.main);
    }

    public static boolean back() {
        UrsafeAccessibilityService service = instance;
        return service != null && service.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public static boolean home() {
        UrsafeAccessibilityService service = instance;
        return service != null && service.performGlobalAction(GLOBAL_ACTION_HOME);
    }
}
