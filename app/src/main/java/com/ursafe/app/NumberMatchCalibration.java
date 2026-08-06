package com.ursafe.app;

import java.nio.ByteBuffer;

public final class NumberMatchCalibration {
    private NumberMatchCalibration() {}

    public static double[] detect(ByteBuffer buffer, int rowStride, int pixelStride,
                                  int width, int height) {
        double fallbackLeft = width * 0.033;
        double fallbackRight = width * 0.967;
        double fallbackTop = height * 0.201;
        int startY = Math.max(1, (int) (height * 0.055));
        int endY = Math.min(height - 2, (int) (height * 0.38));
        int stepX = Math.max(2, width / 240);
        int bestY = -1;
        double bestScore = 0.0;

        for (int y = startY; y <= endY; y++) {
            int line = 0;
            int samples = 0;
            int transitions = 0;
            boolean previous = false;
            for (int x = Math.max(1, width / 80); x < width - width / 80; x += stepX) {
                int value = luma(buffer, rowStride, pixelStride, x, y);
                boolean gridLike = value >= 125 && value <= 246;
                if (gridLike) line++;
                if (samples > 0 && gridLike != previous) transitions++;
                previous = gridLike;
                samples++;
            }
            if (samples == 0) continue;
            double coverage = line / (double) samples;
            double continuityBonus = transitions <= 24 ? 0.12 : 0.0;
            double score = coverage + continuityBonus;
            if (coverage >= 0.48 && score > bestScore) {
                bestScore = score;
                bestY = y;
            }
        }

        if (bestY < 0) {
            return new double[]{fallbackLeft, fallbackRight, fallbackTop,
                    (fallbackRight - fallbackLeft) / 9.0};
        }

        int left = -1;
        int right = -1;
        for (int x = 0; x < width; x++) {
            if (gridPixel(buffer, rowStride, pixelStride, x, bestY)
                    || gridPixel(buffer, rowStride, pixelStride, x, bestY + 1)) {
                if (left < 0) left = x;
                right = x;
            }
        }
        if (left < 0 || right <= left || right - left < width * 0.72) {
            left = (int) fallbackLeft;
            right = (int) fallbackRight;
        }

        double span = right - left;
        double cell = span / 9.0;
        if (cell < width * 0.075 || cell > width * 0.13) {
            left = (int) fallbackLeft;
            right = (int) fallbackRight;
            cell = (right - left) / 9.0;
        }
        return new double[]{left, right, bestY, cell};
    }

    private static boolean gridPixel(ByteBuffer buffer, int rowStride, int pixelStride,
                                     int x, int y) {
        int value = luma(buffer, rowStride, pixelStride, x, y);
        return value >= 120 && value <= 247;
    }

    private static int luma(ByteBuffer buffer, int rowStride, int pixelStride, int x, int y) {
        int offset = y * rowStride + x * pixelStride;
        if (offset < 0 || offset + 2 >= buffer.limit()) return 255;
        int r = buffer.get(offset) & 0xff;
        int g = buffer.get(offset + 1) & 0xff;
        int b = buffer.get(offset + 2) & 0xff;
        return (77 * r + 150 * g + 29 * b) >> 8;
    }
}
