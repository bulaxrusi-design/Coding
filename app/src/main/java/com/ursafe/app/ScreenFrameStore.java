package com.ursafe.app;

public final class ScreenFrameStore {
    private static volatile long latestTimestampMs;
    private static volatile double latestMotion;
    private static volatile long frameCount;
    private static volatile String foregroundPackage = "";

    private ScreenFrameStore() {}

    public static void update(double motion, long timestampMs) {
        latestTimestampMs = timestampMs;
        latestMotion = motion;
        frameCount++;
    }

    public static long timestampMs() { return latestTimestampMs; }
    public static double motion() { return latestMotion; }
    public static long frameCount() { return frameCount; }
    public static void setForegroundPackage(String value) { foregroundPackage = value == null ? "" : value; }
    public static String foregroundPackage() { return foregroundPackage; }
}
