package com.ttclab.chatbridge

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SecurePrefs
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var pollJob: Job? = null
    private val pendingCapture = AtomicReference<CompletableDeferred<ByteArray>?>(null)
    private var screenWidth = 0
    private var screenHeight = 0
    private var sessionId = ""
    private var lastError: String? = null
    private var lastAck: JSONObject? = null

    override fun onCreate() {
        super.onCreate()
        prefs = SecurePrefs(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBridge("Stopped by operator")
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification("Starting bridge…"))

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopBridge("Screen-capture permission missing")
            return START_NOT_STICKY
        }

        if (projection == null) {
            sessionId = UUID.randomUUID().toString()
            prefs.currentSession = sessionId
            prefs.lastSequence = 0L
            prefs.bridgeStatus = "Starting • session ${sessionId.take(8)}"
            startProjection(resultCode, resultData)
            startPolling()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        scope.coroutineContext.cancelChildren()
        prefs.bridgeStatus = "Stopped"
        super.onDestroy()
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData).also { mediaProjection ->
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopBridge("Android stopped screen capture")
                }
            }, null)
        }

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val deferred = pendingCapture.getAndSet(null)
            try {
                if (deferred != null) {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth
                    val paddedWidth = screenWidth + rowPadding / pixelStride
                    val bitmap = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                    val targetWidth = 540
                    val targetHeight = (screenHeight * (targetWidth.toDouble() / screenWidth)).toInt()
                    val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
                    val output = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 60, output)
                    deferred.complete(output.toByteArray())
                    bitmap.recycle(); cropped.recycle(); scaled.recycle()
                }
            } catch (error: Throwable) {
                deferred?.completeExceptionally(error)
            } finally {
                image.close()
            }
        }, null)

        virtualDisplay = projection?.createVirtualDisplay(
            "ChatGPTDeviceLabCapture",
            screenWidth,
            screenHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val token = prefs.getToken()
            if (prefs.owner.isBlank() || prefs.repo.isBlank() || token.isBlank()) {
                stopBridge("GitHub configuration is incomplete")
                return@launch
            }
            val github = GitHubClient(prefs.owner, prefs.repo, prefs.branch, token)
            try {
                initializeSession(github)
                prefs.bridgeStatus = "Connected • session ${sessionId.take(8)}"
                updateNotification("Connected • waiting for target app")
            } catch (error: Throwable) {
                stopBridge("Connection failed: ${error.message}")
                return@launch
            }

            while (isActive) {
                try {
                    publishFrameAndState(github)
                    val pair = github.getText(CONTROL_PATH)
                    if (pair != null) handleCommand(github, JSONObject(pair.first))
                    lastError = null
                    val foreground = BridgeAccessibilityService.foregroundPackage ?: "unknown"
                    updateNotification("Connected • $foreground")
                    prefs.bridgeStatus = "Connected • $foreground • session ${sessionId.take(8)}"
                } catch (error: Throwable) {
                    lastError = error.message ?: error.javaClass.simpleName
                    prefs.bridgeStatus = "Error • ${lastError?.take(120)}"
                    updateNotification("Error: ${lastError?.take(80)}")
                }
                delay((prefs.pollSeconds * 1_000).toLong())
            }
        }
    }

    private suspend fun initializeSession(github: GitHubClient) {
        val initial = JSONObject()
            .put("seq", 0)
            .put("session_id", sessionId)
            .put("action", "observe")
            .put("note", "New foreground session initialized by ChatGPT Device Lab v2")
        github.putText(CONTROL_PATH, initial.toString(2), "Initialize device session ${sessionId.take(8)}")
        publishFrameAndState(github)
    }

    private suspend fun handleCommand(github: GitHubClient, command: JSONObject) {
        val commandSession = command.optString("session_id")
        val seq = command.optLong("seq", -1L)
        if (commandSession != sessionId || seq <= prefs.lastSequence) return

        val expiresAt = command.optString("expires_at")
        val expired = expiresAt.isNotBlank() && runCatching { Instant.parse(expiresAt).isBefore(Instant.now()) }.getOrDefault(true)
        val ack = JSONObject().put("seq", seq).put("session_id", sessionId)
        try {
            if (expired) error("Command expired")
            executeCommand(command)
            ack.put("ok", true).put("message", "completed")
        } catch (error: Throwable) {
            ack.put("ok", false).put("message", error.message ?: error.javaClass.simpleName)
            lastError = error.message
        } finally {
            prefs.lastSequence = seq
            lastAck = ack
            appendAudit(command, ack)
            publishFrameAndState(github)
        }
    }

    private suspend fun executeCommand(command: JSONObject) {
        when (val action = command.optString("action", "observe")) {
            "observe" -> Unit
            "launch" -> launchTarget(command.optString("package", prefs.allowedPackage))
            "tap" -> executeTap(command)
            "swipe" -> executeSwipe(command)
            "back" -> executeBack()
            "wait" -> delay((command.optDouble("seconds", 1.0).coerceIn(0.1, 30.0) * 1_000).toLong())
            "batch" -> executeBatch(command.optJSONArray("actions") ?: JSONArray())
            "panic" -> stopBridge("PANIC command received")
            else -> error("Unsupported action: $action")
        }
    }

    private fun launchTarget(packageName: String) {
        require(packageName == prefs.allowedPackage) { "Package is not allowlisted" }
        require(!isSensitivePackage(packageName)) { "Sensitive package blocked" }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: error("Target app is not installed or not visible")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launchIntent)
    }

    private fun requireSafeForeground(): BridgeAccessibilityService {
        val service = BridgeAccessibilityService.instance ?: error("Accessibility service is not enabled")
        val foreground = BridgeAccessibilityService.foregroundPackage ?: error("Foreground package unknown")
        require(foreground == prefs.allowedPackage) { "Gesture blocked outside allowlisted app: $foreground" }
        require(!isSensitivePackage(foreground)) { "Sensitive surface blocked" }
        return service
    }

    private suspend fun executeTap(command: JSONObject) {
        val service = requireSafeForeground()
        val normalized = command.optBoolean("normalized", false)
        var x = command.getDouble("x").toFloat()
        var y = command.getDouble("y").toFloat()
        if (normalized) { x *= screenWidth; y *= screenHeight }
        require(x in 0f..screenWidth.toFloat() && y in 0f..screenHeight.toFloat()) { "Tap out of bounds" }
        check(service.tap(x, y)) { "Tap gesture failed" }
    }

    private suspend fun executeSwipe(command: JSONObject) {
        val service = requireSafeForeground()
        val normalized = command.optBoolean("normalized", false)
        var x1 = command.getDouble("x1").toFloat()
        var y1 = command.getDouble("y1").toFloat()
        var x2 = command.getDouble("x2").toFloat()
        var y2 = command.getDouble("y2").toFloat()
        if (normalized) {
            x1 *= screenWidth; x2 *= screenWidth; y1 *= screenHeight; y2 *= screenHeight
        }
        require(listOf(x1, x2).all { it in 0f..screenWidth.toFloat() } && listOf(y1, y2).all { it in 0f..screenHeight.toFloat() }) {
            "Swipe out of bounds"
        }
        check(service.swipe(x1, y1, x2, y2, command.optLong("duration_ms", 450))) { "Swipe gesture failed" }
    }

    private fun executeBack() {
        check(requireSafeForeground().back()) { "Back action failed" }
    }

    private suspend fun executeBatch(actions: JSONArray) {
        val limit = minOf(actions.length(), 20)
        for (i in 0 until limit) {
            val item = actions.getJSONObject(i)
            when (item.optString("action")) {
                "tap" -> executeTap(item)
                "swipe" -> executeSwipe(item)
                "back" -> executeBack()
                "wait" -> delay((item.optDouble("seconds", 0.5).coerceIn(0.1, 10.0) * 1_000).toLong())
                else -> error("Unsupported batch action at index $i")
            }
        }
    }

    private fun isSensitivePackage(packageName: String): Boolean {
        val value = packageName.lowercase()
        return listOf("bank", "wallet", "billing", "payment", "vending", "authenticator").any { it in value }
    }

    private suspend fun publishFrameAndState(github: GitHubClient) {
        val foreground = BridgeAccessibilityService.foregroundPackage ?: "unknown"
        val allowed = foreground == prefs.allowedPackage && !isSensitivePackage(foreground)
        val bytes = if (allowed) captureJpeg() else privacyPlaceholder(foreground)
        github.putBytes(SCREENSHOT_PATH, bytes, "Update device frame ${sessionId.take(8)}")
        val state = JSONObject()
            .put("version", 2)
            .put("session_id", sessionId)
            .put("running", true)
            .put("updated_at", Instant.now().toString())
            .put("foreground_package", foreground)
            .put("allowed_package", prefs.allowedPackage)
            .put("screenshot_redacted", !allowed)
            .put("screen_width", screenWidth)
            .put("screen_height", screenHeight)
            .put("last_seq", prefs.lastSequence)
            .put("last_error", lastError ?: JSONObject.NULL)
            .put("ack", lastAck ?: JSONObject.NULL)
        github.putText(STATUS_PATH, state.toString(2), "Update device status ${sessionId.take(8)}")
    }

    private suspend fun captureJpeg(): ByteArray {
        val deferred = CompletableDeferred<ByteArray>()
        pendingCapture.getAndSet(deferred)?.cancel()
        return withTimeout(4_000) { deferred.await() }
    }

    private fun privacyPlaceholder(foreground: String): ByteArray {
        val width = 540
        val height = maxOf(720, (screenHeight * (width.toDouble() / maxOf(screenWidth, 1))).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(30, 30, 30))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
        }
        canvas.drawText("Screenshot hidden", 32f, 80f, paint)
        paint.textSize = 18f
        canvas.drawText("Foreground is not the allowlisted game.", 32f, 125f, paint)
        canvas.drawText(foreground.take(48), 32f, 165f, paint)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun appendAudit(command: JSONObject, ack: JSONObject) {
        runCatching {
            val directory = File(filesDir, "audit").apply { mkdirs() }
            val file = File(directory, "$sessionId.jsonl")
            val line = JSONObject()
                .put("at", Instant.now().toString())
                .put("command", command)
                .put("ack", ack)
                .toString()
            file.appendText(line + "\n")
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("ChatGPT Device Lab active")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            android.R.drawable.ic_delete,
            "STOP",
            PendingIntent.getService(
                this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ChatGPT Device Lab", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun stopBridge(reason: String) {
        prefs.bridgeStatus = reason
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.ttclab.chatbridge.STOP"
        private const val CHANNEL_ID = "chatgpt_device_lab"
        private const val NOTIFICATION_ID = 17029
        private const val CONTROL_PATH = "control/command.json"
        private const val SCREENSHOT_PATH = "state/current.jpg"
        private const val STATUS_PATH = "state/status.json"
    }
}
