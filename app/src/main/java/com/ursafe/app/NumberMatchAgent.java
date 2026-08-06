package com.ursafe.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;

public final class NumberMatchAgent {
    private static final int COLS = 9;
    private static final int MAX_VISIBLE_ROWS = 13;
    private static final int TEMPLATE_W = 20;
    private static final int TEMPLATE_H = 28;
    private static final int TEMPLATE_PIXELS = TEMPLATE_W * TEMPLATE_H;
    private static final long FRAME_INTERVAL_MS = 120L;
    private static final long SECOND_TAP_DELAY_MS = 85L;
    private static final long MOVE_SETTLE_MS = 320L;
    private static final int MAX_MOVES = 400;
    private static final int MAX_ADDITIONS = 5;
    private static final int NOTIFICATION_ID = 6700;
    private static final String CHANNEL = "ursafe_number_match";
    private static final String PREF_TARGET = "number_match_package";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final String[] TEMPLATE_B64 = new String[] {
            "AAAAAeAAfgAP4AH+AHzgB44AYOAADgAA4AAOAADgAA4AAOAADgAA4AAOAADgAA4AAOAADgAA4AAOAADgAA4AAOAADgAAAA==",
            "AAAADwAH/gH/+B8Pg8A8OAHDgBwAAcAAHAABwAA8AAeAAPgADwAD4AB8AA+AAfAAPAAHgADwAB4AA+AAf//H//4//8AAAA==",
            "AAAAH4AP/gH5+B4Pg8A8OAHDgBwAAcAAPAADgAD4Af4AH+AB/4AA+AABwAAcAAHgAB54AcOAHDwDwfn4H/+Af+AAcAAAAA==",
            "AAAAAAAADwAB8AAfAAfwAHcAD3AA5wAccAGHAHhwBwcA4HAOBwGAcDgHB//8f//n//4ABwAAcAAHAABwAAcAAGAAAAAAAA==",
            "AAAB//g//8P//DgAA4AAOAADgAA4AAOAADn4A//wP/+D4Hx4A8OAHAAB4AAOAADgAA54AeeAHDwDwfH4H/+Af+AA8AAAAA==",
            "AAAAD4AD/gD/+A+HweAcPAHDgAA4AAOAADh4Bz/wd/+H8Px8A8fAHHgB54AOOADjgA44AePAHhwDwfj8D/+AP+AA+AAAAA==",
            "AAAH//x//8f//AADwAA4AAOAAHgADwAA8AAeAAHgABwAA8AAPAAHgABwAA8AAOAADgAB4AA8AAPAAHgAB4AAcAAPAAAAAA==",
            "AAAADwAH/gH/+B8Pg8A8OAHDgBw4AcOAHDwDweD4B/4Af+Af/4Hw+DwBw4AceADnAA5wAOeAHjgBw+B8H/+Af/AA8AAAAA==",
            "AAAAPwAP/gH78B4Pg8A4OAPHgBxwAccAHnAB54AeOAPjwD4fH+H/7gf84AAeAAHgABw4AcOAPDwHgfn4H/4Af8AAYAAAAA=="
    };
    private static final byte[][] TEMPLATES = decodeTemplates();

    private static volatile boolean enabled;
    private static volatile String targetPackage = "";
    private static volatile String status = "OFF";
    private static volatile int moves;
    private static volatile int additions;
    private static volatile double confidence;
    private static volatile int recognizedCells;

    private static long lastFrameMs;
    private static long lastActionMs;
    private static String boardBeforeAction = "";
    private static boolean waitingForChange;
    private static int noPairFrames;
    private static int unchangedFrames;

    private NumberMatchAgent() {}

    public static synchronized String start(Context context) {
        if (!ScreenCaptureService.isActive()) return "ჯერ ჩართე ეკრანის დაკვირვება.";
        if (!UrsafeAccessibilityService.isReady()) return "ჯერ ჩართე Accessibility.";
        String packageName = findNumberMatchPackage(context);
        if (packageName.isEmpty()) return "Number Match აპი ვერ მოიძებნა.";
        targetPackage = packageName;
        BridgeCrypto.prefs(context).edit().putString(PREF_TARGET, packageName).apply();
        enabled = true;
        status = "STARTING";
        moves = 0;
        additions = 0;
        confidence = 0.0;
        recognizedCells = 0;
        lastFrameMs = 0L;
        lastActionMs = 0L;
        boardBeforeAction = "";
        waitingForChange = false;
        noPairFrames = 0;
        unchangedFrames = 0;
        showNotification(context);
        launch(context, packageName);
        return "Number Match აგენტი ჩაირთო.";
    }

    public static synchronized void stop(Context context) {
        enabled = false;
        status = "STOPPED";
        waitingForChange = false;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(NOTIFICATION_ID);
    }

    public static boolean isEnabled() { return enabled; }
    public static String status() { return status; }
    public static int moves() { return moves; }
    public static int additions() { return additions; }
    public static double confidence() { return confidence; }
    public static int recognizedCells() { return recognizedCells; }
    public static String targetPackage() { return targetPackage; }

    public static void onFrame(ByteBuffer buffer, int rowStride, int pixelStride,
                               int width, int height, int sourceWidth, int sourceHeight,
                               long nowMs) {
        if (!enabled || nowMs - lastFrameMs < FRAME_INTERVAL_MS) return;
        lastFrameMs = nowMs;

        String foreground = ScreenFrameStore.foregroundPackage();
        String target = targetPackage;
        if (!target.isEmpty() && !foreground.isEmpty() && !target.equals(foreground)) {
            status = "WAITING FOR NUMBER MATCH";
            return;
        }
        if (moves >= MAX_MOVES) {
            enabled = false;
            status = "MOVE LIMIT REACHED";
            return;
        }

        Board board = readBoard(buffer, rowStride, pixelStride, width, height);
        confidence = board.confidence;
        recognizedCells = board.activeCells;
        if (board.activeCells < 2 || board.confidence < 0.72) {
            status = "BOARD NOT READY";
            return;
        }

        if (waitingForChange) {
            if (!board.signature.equals(boardBeforeAction)) {
                waitingForChange = false;
                unchangedFrames = 0;
                noPairFrames = 0;
                status = "BOARD UPDATED";
                return;
            }
            unchangedFrames++;
            if (nowMs - lastActionMs < 1100L) {
                status = "WAITING FOR MOVE";
                return;
            }
            waitingForChange = false;
            if (unchangedFrames > 12) status = "MOVE NOT CONFIRMED";
        }

        if (nowMs - lastActionMs < MOVE_SETTLE_MS) return;
        Pair pair = findPair(board.values, board.rows);
        if (pair != null) {
            dispatchPair(pair, board, width, height, sourceWidth, sourceHeight);
            boardBeforeAction = board.signature;
            waitingForChange = true;
            lastActionMs = nowMs;
            moves++;
            noPairFrames = 0;
            status = String.format(Locale.US, "MOVE %d: %d + %d", moves, pair.a, pair.b);
            return;
        }

        noPairFrames++;
        status = "NO PAIR";
        if (noPairFrames >= 8 && additions < MAX_ADDITIONS) {
            final float x = sourceWidth * 0.50f;
            final float y = sourceHeight * 0.84f;
            MAIN.post(() -> { if (enabled) UrsafeAccessibilityService.tap(x, y, 70L); });
            boardBeforeAction = board.signature;
            waitingForChange = true;
            lastActionMs = nowMs;
            additions++;
            noPairFrames = 0;
            status = "ADDING NUMBERS " + additions;
        }
    }

    private static Board readBoard(ByteBuffer buffer, int rowStride, int pixelStride,
                                   int width, int height) {
        double left = width * 0.033;
        double right = width * 0.967;
        double top = height * 0.201;
        double cell = (right - left) / COLS;
        int rows = Math.min(MAX_VISIBLE_ROWS,
                Math.max(1, (int) Math.floor((height * 0.79 - top) / cell)));
        int[][] values = new int[rows][COLS];
        double confidenceTotal = 0.0;
        int active = 0;
        int lastActiveRow = -1;
        StringBuilder signature = new StringBuilder(rows * COLS);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < COLS; col++) {
                Recognition recognition = recognizeCell(buffer, rowStride, pixelStride,
                        width, height, left, top, cell, row, col);
                values[row][col] = recognition.digit;
                signature.append((char) ('0' + recognition.digit));
                if (recognition.digit > 0) {
                    active++;
                    lastActiveRow = row;
                    confidenceTotal += recognition.confidence;
                }
            }
        }
        int usedRows = Math.max(1, lastActiveRow + 1);
        double averageConfidence = active == 0 ? 0.0 : confidenceTotal / active;
        return new Board(values, usedRows, active, averageConfidence,
                signature.substring(0, usedRows * COLS), left, top, cell);
    }

    private static Recognition recognizeCell(ByteBuffer buffer, int rowStride,
                                             int pixelStride, int width, int height,
                                             double left, double top, double cell,
                                             int row, int col) {
        int x0 = clamp((int) Math.round(left + col * cell + cell * 0.14), 0, width - 1);
        int x1 = clamp((int) Math.round(left + (col + 1) * cell - cell * 0.14), 0, width);
        int y0 = clamp((int) Math.round(top + row * cell + cell * 0.10), 0, height - 1);
        int y1 = clamp((int) Math.round(top + (row + 1) * cell - cell * 0.10), 0, height);

        int minX = x1, maxX = -1, minY = y1, maxY = -1, black = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (luma(buffer, rowStride, pixelStride, x, y) < 130) {
                    black++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (black < 28 || maxX < minX || maxY < minY) return Recognition.EMPTY;
        int boxW = maxX - minX + 1;
        int boxH = maxY - minY + 1;
        if (boxH < cell * 0.35 || boxW < cell * 0.08) return Recognition.EMPTY;

        boolean[] glyph = new boolean[TEMPLATE_PIXELS];
        double scale = Math.min((TEMPLATE_W - 2.0) / boxW, (TEMPLATE_H - 2.0) / boxH);
        int normalizedW = Math.max(1, (int) Math.round(boxW * scale));
        int normalizedH = Math.max(1, (int) Math.round(boxH * scale));
        int offsetX = (TEMPLATE_W - normalizedW) / 2;
        int offsetY = (TEMPLATE_H - normalizedH) / 2;
        for (int ny = 0; ny < normalizedH; ny++) {
            int sourceY = minY + Math.min(boxH - 1,
                    (int) Math.floor((ny + 0.5) * boxH / normalizedH));
            for (int nx = 0; nx < normalizedW; nx++) {
                int sourceX = minX + Math.min(boxW - 1,
                        (int) Math.floor((nx + 0.5) * boxW / normalizedW));
                if (luma(buffer, rowStride, pixelStride, sourceX, sourceY) < 140) {
                    glyph[(offsetY + ny) * TEMPLATE_W + offsetX + nx] = true;
                }
            }
        }

        int bestDigit = 0;
        double best = Double.MAX_VALUE;
        double second = Double.MAX_VALUE;
        for (int digit = 1; digit <= 9; digit++) {
            double distance = shiftedDistance(glyph, TEMPLATES[digit - 1]);
            if (distance < best) {
                second = best;
                best = distance;
                bestDigit = digit;
            } else if (distance < second) {
                second = distance;
            }
        }
        if (best > 0.24 || second - best < 0.020) return Recognition.EMPTY;
        return new Recognition(bestDigit, Math.max(0.0, 1.0 - best));
    }

    private static double shiftedDistance(boolean[] glyph, byte[] template) {
        int best = Integer.MAX_VALUE;
        for (int shiftY = -1; shiftY <= 1; shiftY++) {
            for (int shiftX = -1; shiftX <= 1; shiftX++) {
                int mismatch = 0;
                for (int y = 0; y < TEMPLATE_H; y++) {
                    for (int x = 0; x < TEMPLATE_W; x++) {
                        int sourceX = x - shiftX;
                        int sourceY = y - shiftY;
                        boolean current = sourceX >= 0 && sourceX < TEMPLATE_W
                                && sourceY >= 0 && sourceY < TEMPLATE_H
                                && glyph[sourceY * TEMPLATE_W + sourceX];
                        boolean expected = templateBit(template, y * TEMPLATE_W + x);
                        if (current != expected) mismatch++;
                    }
                }
                if (mismatch < best) best = mismatch;
            }
        }
        return best / (double) TEMPLATE_PIXELS;
    }

    private static Pair findPair(int[][] values, int rows) {
        Pair pair = scanDirections(values, rows, new int[][]{{0, 1}, {1, 0}}, false);
        if (pair != null) return pair;
        pair = scanDirections(values, rows, new int[][]{{1, 1}, {1, -1}}, false);
        if (pair != null) return pair;
        pair = scanDirections(values, rows, new int[][]{{0, 1}, {1, 0}}, true);
        if (pair != null) return pair;
        pair = scanDirections(values, rows, new int[][]{{1, 1}, {1, -1}}, true);
        if (pair != null) return pair;

        Cell previous = null;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < COLS; col++) {
                int value = values[row][col];
                if (value == 0) continue;
                if (previous != null && matches(previous.value, value)) {
                    return new Pair(previous.row, previous.col, row, col,
                            previous.value, value);
                }
                previous = new Cell(row, col, value);
            }
        }
        return null;
    }

    private static Pair scanDirections(int[][] values, int rows, int[][] directions,
                                       boolean allowGaps) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < COLS; col++) {
                int value = values[row][col];
                if (value == 0) continue;
                for (int[] direction : directions) {
                    int rr = row + direction[0];
                    int cc = col + direction[1];
                    while (rr >= 0 && rr < rows && cc >= 0 && cc < COLS) {
                        int other = values[rr][cc];
                        if (other != 0) {
                            if (matches(value, other)) {
                                return new Pair(row, col, rr, cc, value, other);
                            }
                            break;
                        }
                        if (!allowGaps) break;
                        rr += direction[0];
                        cc += direction[1];
                    }
                }
            }
        }
        return null;
    }

    private static boolean matches(int a, int b) {
        return a > 0 && b > 0 && (a == b || a + b == 10);
    }

    private static void dispatchPair(Pair pair, Board board, int captureWidth,
                                     int captureHeight, int sourceWidth, int sourceHeight) {
        final float firstX = mapX(board.left + (pair.col1 + 0.5) * board.cell,
                captureWidth, sourceWidth);
        final float firstY = mapY(board.top + (pair.row1 + 0.5) * board.cell,
                captureHeight, sourceHeight);
        final float secondX = mapX(board.left + (pair.col2 + 0.5) * board.cell,
                captureWidth, sourceWidth);
        final float secondY = mapY(board.top + (pair.row2 + 0.5) * board.cell,
                captureHeight, sourceHeight);
        MAIN.post(() -> {
            if (!enabled) return;
            UrsafeAccessibilityService.tap(firstX, firstY, 55L);
            MAIN.postDelayed(() -> {
                if (enabled) UrsafeAccessibilityService.tap(secondX, secondY, 55L);
            }, SECOND_TAP_DELAY_MS);
        });
    }

    private static String findNumberMatchPackage(Context context) {
        String saved = BridgeCrypto.prefs(context).getString(PREF_TARGET, "");
        if (saved != null && !saved.isEmpty()
                && context.getPackageManager().getLaunchIntentForPackage(saved) != null) return saved;
        PackageManager manager = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> candidates = manager.queryIntentActivities(query, PackageManager.MATCH_ALL);
        String fallback = "";
        for (ResolveInfo info : candidates) {
            CharSequence labelValue = info.loadLabel(manager);
            String label = labelValue == null ? "" : labelValue.toString();
            String normalized = label.toLowerCase(Locale.ROOT).replace("-", " ").trim();
            String packageName = info.activityInfo == null ? "" : info.activityInfo.packageName;
            if (normalized.equals("number match")) return packageName;
            if (normalized.contains("number") && normalized.contains("match")) fallback = packageName;
        }
        return fallback;
    }

    private static void launch(Context context, String packageName) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(launch);
    }

    private static void showNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "Number Match agent", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("ადგილობრივი Number Match მოთამაშის სტატუსი");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Intent stop = new Intent(context, BridgeActionReceiver.class)
                .setAction(BridgeActionReceiver.ACTION_STOP_NUMBER_MATCH);
        PendingIntent stopIntent = PendingIntent.getBroadcast(context, 6701, stop,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_ursafe_logo)
                .setContentTitle("Number Match agent აქტიურია")
                .setContentText("ეკრანი ადგილობრივად მუშავდება")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(0, "შეჩერება", stopIntent).build())
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    private static byte[][] decodeTemplates() {
        byte[][] output = new byte[TEMPLATE_B64.length][];
        for (int i = 0; i < TEMPLATE_B64.length; i++) {
            output[i] = Base64.decode(TEMPLATE_B64[i], Base64.DEFAULT);
        }
        return output;
    }

    private static boolean templateBit(byte[] template, int index) {
        return (template[index / 8] & (1 << (7 - index % 8))) != 0;
    }

    private static int luma(ByteBuffer buffer, int rowStride, int pixelStride, int x, int y) {
        int offset = y * rowStride + x * pixelStride;
        if (offset < 0 || offset + 2 >= buffer.limit()) return 255;
        int r = buffer.get(offset) & 0xff;
        int g = buffer.get(offset + 1) & 0xff;
        int b = buffer.get(offset + 2) & 0xff;
        return (77 * r + 150 * g + 29 * b) >> 8;
    }

    private static float mapX(double x, int captureWidth, int sourceWidth) {
        return (float) (x * sourceWidth / Math.max(1.0, captureWidth));
    }

    private static float mapY(double y, int captureHeight, int sourceHeight) {
        return (float) (y * sourceHeight / Math.max(1.0, captureHeight));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Recognition {
        static final Recognition EMPTY = new Recognition(0, 0.0);
        final int digit;
        final double confidence;
        Recognition(int digit, double confidence) {
            this.digit = digit;
            this.confidence = confidence;
        }
    }

    private static final class Board {
        final int[][] values;
        final int rows;
        final int activeCells;
        final double confidence;
        final String signature;
        final double left;
        final double top;
        final double cell;
        Board(int[][] values, int rows, int activeCells, double confidence,
              String signature, double left, double top, double cell) {
            this.values = values;
            this.rows = rows;
            this.activeCells = activeCells;
            this.confidence = confidence;
            this.signature = signature;
            this.left = left;
            this.top = top;
            this.cell = cell;
        }
    }

    private static final class Pair {
        final int row1;
        final int col1;
        final int row2;
        final int col2;
        final int a;
        final int b;
        Pair(int row1, int col1, int row2, int col2, int a, int b) {
            this.row1 = row1;
            this.col1 = col1;
            this.row2 = row2;
            this.col2 = col2;
            this.a = a;
            this.b = b;
        }
    }

    private static final class Cell {
        final int row;
        final int col;
        final int value;
        Cell(int row, int col, int value) {
            this.row = row;
            this.col = col;
            this.value = value;
        }
    }
}
