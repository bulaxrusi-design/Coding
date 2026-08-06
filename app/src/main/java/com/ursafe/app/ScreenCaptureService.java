package com.ursafe.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public final class ScreenCaptureService extends Service {
    private static final String ACTION_START = "com.ursafe.app.capture.START";
    private static final String ACTION_STOP = "com.ursafe.app.capture.STOP";
    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_RESULT_DATA = "result_data";
    private static final String CHANNEL = "ursafe_screen_observer";
    private static final int NOTIFICATION_ID = 6500;
    private static final long FRAME_INTERVAL_MS = 80L;
    private static final long JPEG_INTERVAL_MS = 280L;
    private static volatile boolean active;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;
    private long lastProcessedMs;
    private long lastJpegMs;
    private byte[] previousLuma;

    public static void start(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, ScreenCaptureService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, ScreenCaptureService.class).setAction(ACTION_STOP));
    }

    public static boolean isActive() { return active; }

    @Override public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("ursafe-screen-observer");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        startProjectionForeground();
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultCode == 0 || resultData == null) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startProjection(resultCode, resultData);
            return START_STICKY;
        } catch (Exception error) {
            BridgeNotifications.showMessage(this, "Screen observer შეცდომა", String.valueOf(error.getMessage()), 6501);
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void startProjectionForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Ursafe screen observer", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("ეკრანის ადგილობრივი ანალიზი მომხმარებლის თანხმობით");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Intent stop = new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(
                this, 6502, stop, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_ursafe_logo)
                .setContentTitle("Ursafe ეკრანს ადგილობრივად აკვირდება")
                .setContentText("კადრები მხოლოდ დამტკიცებული მოთხოვნით ბრუნდება")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(0, "შეჩერება", stopIntent).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startProjection(int resultCode, Intent resultData) {
        shutdownProjectionOnly();
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) throw new IllegalStateException("MediaProjection unavailable");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                worker.post(() -> {
                    shutdownProjectionOnly();
                    stopSelf();
                });
            }
        }, worker);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager window = (WindowManager) getSystemService(WINDOW_SERVICE);
        window.getDefaultDisplay().getRealMetrics(metrics);
        int sourceWidth = Math.max(1, metrics.widthPixels);
        int sourceHeight = Math.max(1, metrics.heightPixels);
        ScreenFrameStore.setScreenSize(sourceWidth, sourceHeight);
        int captureWidth = Math.min(720, sourceWidth);
        int captureHeight = Math.max(1, Math.round(sourceHeight * (captureWidth / (float) sourceWidth)));

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, worker);
        virtualDisplay = projection.createVirtualDisplay(
                "UrsafeLocalObserver",
                captureWidth,
                captureHeight,
                Math.max(1, metrics.densityDpi),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                worker);
        active = true;
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            long now = System.currentTimeMillis();
            if (now - lastProcessedMs < FRAME_INTERVAL_MS) return;
            lastProcessedMs = now;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int width = image.getWidth();
            int height = image.getHeight();
            final int gridW = 64;
            final int gridH = 36;
            byte[] current = new byte[gridW * gridH];
            long totalDiff = 0;
            for (int gy = 0; gy < gridH; gy++) {
                int y = gy * Math.max(1, height - 1) / Math.max(1, gridH - 1);
                for (int gx = 0; gx < gridW; gx++) {
                    int x = gx * Math.max(1, width - 1) / Math.max(1, gridW - 1);
                    int offset = y * rowStride + x * pixelStride;
                    int r = buffer.get(offset) & 0xff;
                    int g = buffer.get(offset + 1) & 0xff;
                    int b = buffer.get(offset + 2) & 0xff;
                    int luma = (77 * r + 150 * g + 29 * b) >> 8;
                    int index = gy * gridW + gx;
                    current[index] = (byte) luma;
                    if (previousLuma != null) {
                        totalDiff += Math.abs(luma - (previousLuma[index] & 0xff));
                    }
                }
            }
            double motion = previousLuma == null ? 0.0
                    : totalDiff / (255.0 * current.length);
            previousLuma = current;
            ScreenFrameStore.update(motion, now);

            if (now - lastJpegMs >= JPEG_INTERVAL_MS) {
                lastJpegMs = now;
                byte[] jpeg = encodeCompactJpeg(buffer, rowStride, pixelStride, width, height);
                if (jpeg != null && jpeg.length > 0) ScreenFrameStore.updateJpeg(jpeg, now);
            }
        } catch (Exception ignored) {
            // Frame drops are expected under load and must not stop the observer.
        } finally {
            if (image != null) image.close();
        }
    }

    private static byte[] encodeCompactJpeg(ByteBuffer source, int rowStride,
                                            int pixelStride, int width, int height) {
        Bitmap padded = null;
        Bitmap cropped = null;
        Bitmap compact = null;
        try {
            int paddedWidth = Math.max(width, rowStride / Math.max(1, pixelStride));
            padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            source.rewind();
            padded.copyPixelsFromBuffer(source);
            cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            int targetWidth = Math.min(540, width);
            int targetHeight = Math.max(1, Math.round(height * (targetWidth / (float) width)));
            compact = targetWidth == width ? cropped
                    : Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true);
            ByteArrayOutputStream output = new ByteArrayOutputStream(96_000);
            compact.compress(Bitmap.CompressFormat.JPEG, 62, output);
            byte[] data = output.toByteArray();
            if (data.length > 220_000 && targetWidth > 360) {
                Bitmap smaller = Bitmap.createScaledBitmap(cropped, 360,
                        Math.max(1, Math.round(height * (360f / width))), true);
                output.reset();
                smaller.compress(Bitmap.CompressFormat.JPEG, 55, output);
                data = output.toByteArray();
                smaller.recycle();
            }
            return data;
        } finally {
            if (compact != null && compact != cropped) compact.recycle();
            if (cropped != null) cropped.recycle();
            if (padded != null) padded.recycle();
        }
    }

    private void shutdownProjectionOnly() {
        active = false;
        previousLuma = null;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
    }

    private void shutdown() {
        shutdownProjectionOnly();
        stopForeground(true);
    }

    @Override public void onDestroy() {
        shutdown();
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }
}
