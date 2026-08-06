package com.ursafe.app;

public final class ScreenFrameStore {
    private static volatile long latestTimestampMs;
    private static volatile double latestMotion;
    private static volatile long frameCount;
    private static volatile String foregroundPackage = "";
    private static volatile int screenWidth;
    private static volatile int screenHeight;
    private static volatile byte[] latestJpeg;
    private static volatile long jpegTimestampMs;

    private ScreenFrameStore() {}

    public static void update(double motion, long timestampMs) {
        latestTimestampMs = timestampMs;
        latestMotion = motion;
        frameCount++;
        QaSessionManager.onFrame(foregroundPackage, timestampMs, motion);
    }

    public static synchronized void updateJpeg(byte[] jpeg, long timestampMs) {
        latestJpeg = jpeg == null ? null : jpeg.clone();
        jpegTimestampMs = timestampMs;
    }

    public static synchronized byte[] latestJpeg() {
        return latestJpeg == null ? null : latestJpeg.clone();
    }

    public static void setScreenSize(int width, int height) {
        screenWidth = Math.max(0, width);
        screenHeight = Math.max(0, height);
    }

    public static long timestampMs() { return latestTimestampMs; }
    public static long jpegTimestampMs() { return jpegTimestampMs; }
    public static double motion() { return latestMotion; }
    public static long frameCount() { return frameCount; }
    public static int screenWidth() { return screenWidth; }
    public static int screenHeight() { return screenHeight; }
    public static void setForegroundPackage(String value) { foregroundPackage = value == null ? "" : value; }
    public static String foregroundPackage() { return foregroundPackage; }
}
