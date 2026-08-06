package com.ttclab.chatbridge

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SecurePrefs

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var bridgeJob: Job? = null

    private val pendingCapture = AtomicReference<CompletableDeferred<ByteArray>?>(null)
    private val frameMutex = Mutex()
    private val statusMutex = Mutex()

    private var screenWidth = 0
    private var screenHeight = 0
    private var encodedWidth = 0
    private var encodedHeight = 0

    private var sessionId = ""
    private var frameId = 0L
    private var lastError: String? = null
    private var lastAck: JSONObject? = null
    private var lastCaptureOk = false
    private var lastCaptureError: String? = null

    private var lastFrameSignature: String? = null
    private var lastFramePublishedAt = 0L
    private var lastStatusSignature: String? = null
    private var lastStatusPublishedAt = 0L

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

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopBridge("Screen-capture permission missing")
            return START_NOT_STICKY
        }

        startForegroundCompat("Starting optimized bridge…")

        if (projection == null) {
            sessionId = UUID.randomUUID().toString()
            prefs.currentSession = sessionId
            prefs.lastSequence = 0L
            prefs.bridgeStatus = "Starting v2.3 • session ${sessionId.take(8)}"
            startProjection(resultCode, resultData)
            startBridgeLoops()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bridgeJob?.cancel()
        pendingCapture.getAndSet(null)?.cancel()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
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
        encodedWidth = minOf(FRAME_WIDTH, screenWidth)
        encodedHeight = maxOf(1, (screenHeight * (encodedWidth.toDouble() / screenWidth)).toInt())

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
                    val scaled = Bitmap.createScaledBitmap(cropped, encodedWidth, encodedHeight, true)
                    val output = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, output)
                    deferred.complete(output.toByteArray())
                    bitmap.recycle()
                    cropped.recycle()
                    scaled.recycle()
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

    private fun startBridgeLoops() {
        bridgeJob?.cancel()
        bridgeJob = scope.launch {
            val token = prefs.getToken()
            if (prefs.owner.isBlank() || prefs.repo.isBlank() || token.isBlank()) {
                stopBridge("GitHub configuration is incomplete")
                return@launch
            }

            val controlClient = GitHubClient(prefs.owner, prefs.repo, CONTROL_BRANCH, token)
            val stateClient = GitHubClient(prefs.owner, prefs.repo, STATE_BRANCH, token)
            val frameClient = GitHubClient(prefs.owner, prefs.repo, FRAME_BRANCH, token)

            try {
                val resolution = ForegroundResolver.resolve(this@BridgeService)
                publishStatus(stateClient, resolution, force = true)
                publishFrame(frameClient, resolution, force = true)
                prefs.bridgeStatus = "Connected v2.3 • session ${sessionId.take(8)}"
                updateNotification("Connected • fast command channel ready")
            } catch (error: Throwable) {
                stopBridge("Connection failed: ${error.message}")
                return@launch
            }

            launch {
                while (isActive) {
                    try {
                        val pair = controlClient.getText(CONTROL_PATH)
                        if (pair != null) {
                            handleCommand(
                                command = JSONObject(pair.first),
                                stateClient = stateClient,
                                frameClient = frameClient
                            )
                        }
                    } catch (error: Throwable) {
                        lastError = error.message ?: error.javaClass.simpleName
                        prefs.bridgeStatus = "Command channel error • ${lastError?.take(100)}"
                        runCatching {
                            publishStatus(
                                stateClient,
                                ForegroundResolver.resolve(this@BridgeService),
                                force = true
                            )
                        }
                    }
                    delay(COMMAND_POLL_MS)
                }
            }

            launch {
                while (isActive) {
                    try {
                        val resolution = ForegroundResolver.resolve(this@BridgeService)
                        publishFrame(frameClient, resolution, force = false)
                        publishStatus(stateClient, resolution, force = false)

                        val foreground = resolution.packageName ?: "unknown"
                        updateNotification("Connected • $foreground • ${resolution.source}")
                        prefs.bridgeStatus =
                            "Connected v2.3 • $foreground • ${resolution.source} • session ${sessionId.take(8)}"
                    } catch (error: Throwable) {
                        lastError = error.message ?: error.javaClass.simpleName
                        prefs.bridgeStatus = "Frame channel error • ${lastError?.take(100)}"
                        updateNotification("Frame error: ${lastError?.take(70)}")
                    }
                    delay(BACKGROUND_FRAME_POLL_MS)
                }
            }
        }
    }

    private suspend fun handleCommand(
        command: JSONObject,
        stateClient: GitHubClient,
        frameClient: GitHubClient
    ) {
        val commandSession = command.optString("session_id")
        val seq = command.optLong("seq", -1L)
        if (commandSession != sessionId || seq <= prefs.lastSequence) return

        val expiresAt = command.optString("expires_at")
        val expired = expiresAt.isNotBlank() && runCatching {
            Instant.parse(expiresAt).isBefore(Instant.now())
        }.getOrDefault(true)

        val action = command.optString("action", "observe")
        val ack = JSONObject()
            .put("seq", seq)
            .put("session_id", sessionId)
            .put("action", action)
            .put("started_at", Instant.now().toString())

        try {
            if (expired) error("Command expired")
            executeCommand(command)
            val settleMs = command.optLong("settle_ms", defaultSettleMs(action))
                .coerceIn(0L, 3_000L)
            if (settleMs > 0) delay(settleMs)
            lastError = null
            ack.put("ok", true).put("message", "completed")
        } catch (error: Throwable) {
            lastError = error.message ?: error.javaClass.simpleName
            ack.put("ok", false).put("message", lastError)
        } finally {
            ack.put("finished_at", Instant.now().toString())
            prefs.lastSequence = seq
            lastAck = ack
            appendAudit(command, ack)

            val resolution = ForegroundResolver.resolve(this)
            runCatching { publishStatus(stateClient, resolution, force = true) }
                .onFailure { lastError = it.message ?: it.javaClass.simpleName }
            runCatching { publishFrame(frameClient, resolution, force = true) }
                .onFailure {
                    lastCaptureOk = false
                    lastCaptureError = it.message ?: it.javaClass.simpleName
                }
        }
    }

    private fun defaultSettleMs(action: String): Long = when (action) {
        "launch" -> 900L
        "tap", "swipe", "back", "batch" -> 260L
        else -> 0L
    }

    private suspend fun executeCommand(command: JSONObject) {
        when (val action = command.optString("action", "observe")) {
            "observe", "diagnose" -> Unit
            "launch" -> launchTarget(command.optString("package", prefs.allowedPackage))
            "tap" -> executeTap(command)
            "swipe" -> executeSwipe(command)
            "back" -> executeBack()
            "wait" -> delay(
                (command.optDouble("seconds", 1.0).coerceIn(0.05, 30.0) * 1_000).toLong()
            )
            "batch" -> executeBatch(command.optJSONArray("actions") ?: JSONArray())
            "panic" -> stopBridge("PANIC command received")
            else -> error("Unsupported action: $action")
        }
    }

    private fun launchTarget(packageName: String) {
        require(packageName == prefs.allowedPackage) { "Package is not allowlisted" }
        require(!CommandPolicy.isSensitivePackage(packageName)) { "Sensitive package blocked" }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: error("Target app is not installed or not visible")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launchIntent)
    }

    private fun requireSafeForeground(): BridgeAccessibilityService {
        val service = BridgeAccessibilityService.instance
            ?: error("Accessibility service is not enabled")
        val resolution = ForegroundResolver.resolve(this)
        val foreground = resolution.packageName
            ?: error("Foreground package unknown; enable Usage Access and Accessibility")
        require(CommandPolicy.isAllowedForeground(foreground, prefs.allowedPackage)) {
            "Gesture blocked outside allowlisted app: $foreground (${resolution.source})"
        }
        return service
    }

    private suspend fun executeTap(command: JSONObject) {
        val service = requireSafeForeground()
        val normalized = command.optBoolean("normalized", false)
        var x = command.getDouble("x").toFloat()
        var y = command.getDouble("y").toFloat()
        if (normalized) {
            x *= screenWidth
            y *= screenHeight
        }
        require(CommandPolicy.isPointInside(x, y, screenWidth, screenHeight)) {
            "Tap out of bounds"
        }
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
            x1 *= screenWidth
            x2 *= screenWidth
            y1 *= screenHeight
            y2 *= screenHeight
        }
        require(CommandPolicy.isSwipeInside(x1, y1, x2, y2, screenWidth, screenHeight)) {
            "Swipe out of bounds"
        }
        check(service.swipe(x1, y1, x2, y2, command.optLong("duration_ms", 350))) {
            "Swipe gesture failed"
        }
    }

    private fun executeBack() {
        check(requireSafeForeground().back()) { "Back action failed" }
    }

    private suspend fun executeBatch(actions: JSONArray) {
        val limit = minOf(actions.length(), 40)
        for (i in 0 until limit) {
            val item = actions.getJSONObject(i)
            when (item.optString("action")) {
                "tap" -> executeTap(item)
                "swipe" -> executeSwipe(item)
                "back" -> executeBack()
                "wait" -> delay(
                    (item.optDouble("seconds", 0.25).coerceIn(0.05, 10.0) * 1_000).toLong()
                )
                else -> error("Unsupported batch action at index $i")
            }
            val afterMs = item.optLong("after_ms", 80L).coerceIn(0L, 2_000L)
            if (afterMs > 0) delay(afterMs)
        }
    }

    private suspend fun publishFrame(
        frameClient: GitHubClient,
        resolution: ForegroundResolution,
        force: Boolean
    ) = frameMutex.withLock {
        val nowMs = System.currentTimeMillis()
        val foreground = resolution.packageName ?: "unknown"
        val allowed = CommandPolicy.isAllowedForeground(resolution.packageName, prefs.allowedPackage)

        if (!allowed) {
            lastCaptureOk = false
            lastCaptureError = null
            val signature = "redacted|$foreground|${resolution.source}"
            if (!force &&
                signature == lastFrameSignature &&
                nowMs - lastFramePublishedAt < FRAME_HEARTBEAT_MS
            ) {
                return@withLock
            }

            frameId += 1
            val envelope = JSONObject()
                .put("protocol_version", 4)
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("session_id", sessionId)
                .put("frame_id", frameId)
                .put("captured_at", Instant.now().toString())
                .put("foreground_package", foreground)
                .put("foreground_source", resolution.source)
                .put("allowed_package", prefs.allowedPackage)
                .put("redacted", true)
                .put("screen_width", screenWidth)
                .put("screen_height", screenHeight)
                .put("image_width", JSONObject.NULL)
                .put("image_height", JSONObject.NULL)
                .put("mime_type", JSONObject.NULL)
                .put("jpeg_sha256", JSONObject.NULL)
                .put("jpeg_base64", JSONObject.NULL)

            frameClient.putText(
                FRAME_PATH,
                envelope.toString(),
                "Update redacted frame ${sessionId.take(8)}"
            )
            lastFrameSignature = signature
            lastFramePublishedAt = nowMs
            return@withLock
        }

        val capture = runCatching { captureJpeg() }
        if (capture.isFailure) {
            lastCaptureOk = false
            lastCaptureError = capture.exceptionOrNull()?.message
            throw capture.exceptionOrNull() ?: IllegalStateException("Screen capture failed")
        }

        val bytes = capture.getOrThrow()
        val hash = sha256(bytes)
        val signature = "$foreground|$hash"
        lastCaptureOk = true
        lastCaptureError = null

        if (!force &&
            signature == lastFrameSignature &&
            nowMs - lastFramePublishedAt < FRAME_HEARTBEAT_MS
        ) {
            return@withLock
        }

        frameId += 1
        val envelope = JSONObject()
            .put("protocol_version", 4)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("session_id", sessionId)
            .put("frame_id", frameId)
            .put("captured_at", Instant.now().toString())
            .put("foreground_package", foreground)
            .put("foreground_source", resolution.source)
            .put("allowed_package", prefs.allowedPackage)
            .put("redacted", false)
            .put("screen_width", screenWidth)
            .put("screen_height", screenHeight)
            .put("image_width", encodedWidth)
            .put("image_height", encodedHeight)
            .put("mime_type", "image/jpeg")
            .put("jpeg_sha256", hash)
            .put("jpeg_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))

        frameClient.putText(
            FRAME_PATH,
            envelope.toString(),
            "Update readable frame ${sessionId.take(8)} #$frameId"
        )
        lastFrameSignature = signature
        lastFramePublishedAt = nowMs
    }

    private suspend fun publishStatus(
        stateClient: GitHubClient,
        resolution: ForegroundResolution,
        force: Boolean
    ) = statusMutex.withLock {
        val nowMs = System.currentTimeMillis()
        val foreground = resolution.packageName ?: "unknown"
        val allowed = CommandPolicy.isAllowedForeground(resolution.packageName, prefs.allowedPackage)
        val targetInstalled = packageManager.getLaunchIntentForPackage(prefs.allowedPackage) != null
        val projectionActive = projection != null && virtualDisplay != null && imageReader != null
        val gestureReady = allowed && resolution.accessibilityConnected

        val ackText = lastAck?.toString() ?: ""
        val signature = listOf(
            foreground,
            resolution.source,
            allowed,
            targetInstalled,
            projectionActive,
            gestureReady,
            lastCaptureOk,
            lastCaptureError,
            prefs.lastSequence,
            lastError,
            ackText,
            frameId
        ).joinToString("|")

        if (!force &&
            signature == lastStatusSignature &&
            nowMs - lastStatusPublishedAt < STATUS_HEARTBEAT_MS
        ) {
            return@withLock
        }

        val selfTest = JSONObject()
            .put("target_installed", targetInstalled)
            .put("accessibility_connected", resolution.accessibilityConnected)
            .put("usage_access_granted", resolution.usageAccessGranted)
            .put("projection_active", projectionActive)
            .put("foreground_known", resolution.packageName != null)
            .put("target_in_foreground", allowed)
            .put("capture_ok", lastCaptureOk)
            .put("gesture_ready", gestureReady)

        val state = JSONObject()
            .put("protocol_version", 4)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("session_id", sessionId)
            .put("running", true)
            .put("updated_at", Instant.now().toString())
            .put("foreground_package", foreground)
            .put("foreground_source", resolution.source)
            .put("foreground_age_ms", resolution.ageMs ?: JSONObject.NULL)
            .put("allowed_package", prefs.allowedPackage)
            .put("screenshot_redacted", !allowed || !lastCaptureOk)
            .put("screen_width", screenWidth)
            .put("screen_height", screenHeight)
            .put("frame_id", frameId)
            .put("frame_path", FRAME_PATH)
            .put("control_branch", CONTROL_BRANCH)
            .put("state_branch", STATE_BRANCH)
            .put("frame_branch", FRAME_BRANCH)
            .put("command_poll_ms", COMMAND_POLL_MS)
            .put("last_seq", prefs.lastSequence)
            .put("last_error", lastError ?: JSONObject.NULL)
            .put("capture_error", lastCaptureError ?: JSONObject.NULL)
            .put("ack", lastAck ?: JSONObject.NULL)
            .put("self_test", selfTest)
            .put(
                "capabilities",
                JSONArray(
                    listOf(
                        "observe",
                        "diagnose",
                        "launch",
                        "tap",
                        "swipe",
                        "back",
                        "wait",
                        "batch",
                        "panic",
                        "readable_frame_json",
                        "split_relay_branches"
                    )
                )
            )

        stateClient.putText(
            STATUS_PATH,
            state.toString(2),
            "Update device status ${sessionId.take(8)}"
        )
        lastStatusSignature = signature
        lastStatusPublishedAt = nowMs
    }

    private suspend fun captureJpeg(): ByteArray {
        check(projection != null && virtualDisplay != null && imageReader != null) {
            "MediaProjection is not active"
        }
        val deferred = CompletableDeferred<ByteArray>()
        pendingCapture.getAndSet(deferred)?.cancel()
        return withTimeout(4_000) { deferred.await() }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

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
        .setContentTitle("ChatGPT Device Lab v2.3 active")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            android.R.drawable.ic_delete,
            "STOP",
            PendingIntent.getService(
                this,
                1,
                Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun startForegroundCompat(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ChatGPT Device Lab",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
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

        private const val CONTROL_BRANCH = "device-control"
        private const val STATE_BRANCH = "device-state"
        private const val FRAME_BRANCH = "device-frames"

        private const val CONTROL_PATH = "control/command.json"
        private const val STATUS_PATH = "state/status.json"
        private const val FRAME_PATH = "state/current_frame.json"

        private const val COMMAND_POLL_MS = 1_100L
        private const val BACKGROUND_FRAME_POLL_MS = 2_000L
        private const val FRAME_HEARTBEAT_MS = 8_000L
        private const val STATUS_HEARTBEAT_MS = 15_000L

        private const val FRAME_WIDTH = 480
        private const val FRAME_JPEG_QUALITY = 55
    }
}
