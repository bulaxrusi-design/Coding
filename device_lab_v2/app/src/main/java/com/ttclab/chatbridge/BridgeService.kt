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
import kotlin.math.roundToLong

class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SecurePrefs

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var bridgeJob: Job? = null

    private val pendingCapture = AtomicReference<CompletableDeferred<CapturedFrame>?>(null)
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
    private var lastVisionOk = false
    private var lastVisionError: String? = null
    private var lastOcrElementCount = 0
    private var lastNumberCount = 0
    private var lastAccessibilityNodeCount = 0
    private var lastFrameChunkCount = 0
    private var lastVision: JSONObject? = null

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

        startForegroundCompat("Starting final vision bridge…")

        if (projection == null) {
            sessionId = UUID.randomUUID().toString()
            prefs.currentSession = sessionId
            prefs.lastSequence = 0L
            prefs.bridgeStatus = "Starting v2.4.1 • session ${sessionId.take(8)}"
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
                    val padded = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight)
                    val scaled = Bitmap.createScaledBitmap(cropped, encodedWidth, encodedHeight, true)
                    val output = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, output)
                    val accepted = deferred.complete(CapturedFrame(output.toByteArray(), scaled))
                    if (!accepted) scaled.recycle()
                    padded.recycle()
                    cropped.recycle()
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

            val initialResolution = ForegroundResolver.resolve(this@BridgeService)
            try {
                // The lightweight state channel is the connection source of truth. Publish it first
                // so a temporary frame/OCR failure cannot make a healthy bridge look disconnected.
                publishStatus(stateClient, initialResolution, force = true)
            } catch (error: Throwable) {
                stopBridge("Connection failed: ${error.message}")
                return@launch
            }

            runCatching { publishFrame(frameClient, initialResolution, force = true) }
                .onFailure { error ->
                    lastCaptureOk = false
                    lastCaptureError = error.message ?: error.javaClass.simpleName
                    lastError = "Frame channel: $lastCaptureError"
                    runCatching { publishStatus(stateClient, initialResolution, force = true) }
                }

            prefs.bridgeStatus = "Connected v2.4.1 • session ${sessionId.take(8)}"
            updateNotification(
                if (lastCaptureError == null) "Connected • OCR and gestures ready"
                else "Connected • frame retry active"
            )

            launch {
                while (isActive) {
                    val cycleStarted = System.currentTimeMillis()
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
                    val elapsed = System.currentTimeMillis() - cycleStarted
                    delay((COMMAND_POLL_MS - elapsed).coerceAtLeast(MIN_LOOP_DELAY_MS))
                }
            }

            launch {
                while (isActive) {
                    val cycleStarted = System.currentTimeMillis()
                    val resolution = ForegroundResolver.resolve(this@BridgeService)
                    val frameFailure = runCatching {
                        publishFrame(frameClient, resolution, force = false)
                    }.exceptionOrNull()

                    if (frameFailure != null) {
                        lastCaptureOk = false
                        lastCaptureError = frameFailure.message ?: frameFailure.javaClass.simpleName
                        lastError = "Frame channel: $lastCaptureError"
                    } else if (lastError?.startsWith("Frame channel:") == true) {
                        lastError = null
                    }

                    val statusFailure = runCatching {
                        publishStatus(stateClient, resolution, force = frameFailure != null)
                    }.exceptionOrNull()

                    val foreground = resolution.packageName ?: "unknown"
                    if (statusFailure == null) {
                        prefs.bridgeStatus = if (frameFailure == null) {
                            "Connected v2.4.1 • $foreground • ${resolution.source} • session ${sessionId.take(8)}"
                        } else {
                            "Connected v2.4.1 • state OK • frame retry • ${lastCaptureError?.take(70)}"
                        }
                        updateNotification(
                            if (frameFailure == null) "Connected • $foreground • OCR $lastNumberCount numbers"
                            else "Connected • frame retry active"
                        )
                    } else {
                        lastError = statusFailure.message ?: statusFailure.javaClass.simpleName
                        prefs.bridgeStatus = "State channel error • ${lastError?.take(100)}"
                        updateNotification("State error: ${lastError?.take(65)}")
                    }
                    val elapsed = System.currentTimeMillis() - cycleStarted
                    delay((BACKGROUND_FRAME_POLL_MS - elapsed).coerceAtLeast(MIN_LOOP_DELAY_MS))
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
            .put("received_at", Instant.now().toString())

        try {
            if (expired) error("Command expired")
            val startedMs = System.currentTimeMillis()
            executeCommand(command)
            val settleMs = command.optLong("settle_ms", defaultSettleMs(action))
                .coerceIn(0L, 3_000L)
            if (settleMs > 0) delay(settleMs)
            lastError = null
            ack
                .put("ok", true)
                .put("message", "completed")
                .put("gesture_latency_ms", System.currentTimeMillis() - startedMs)
        } catch (error: Throwable) {
            lastError = error.message ?: error.javaClass.simpleName
            ack.put("ok", false).put("message", lastError)
        } finally {
            ack.put("finished_at", Instant.now().toString())
            prefs.lastSequence = seq
            lastAck = ack
            appendAudit(command, ack)

            val resolution = ForegroundResolver.resolve(this)
            runCatching { publishFrame(frameClient, resolution, force = true) }
                .onFailure {
                    lastCaptureOk = false
                    lastCaptureError = it.message ?: it.javaClass.simpleName
                }
            runCatching { publishStatus(stateClient, resolution, force = true) }
                .onFailure { lastError = it.message ?: it.javaClass.simpleName }
        }
    }

    private fun defaultSettleMs(action: String): Long = when (action) {
        "launch" -> 750L
        "tap", "tap_text", "tap_number", "swipe", "back" -> 140L
        "batch" -> 100L
        else -> 0L
    }

    private suspend fun executeCommand(command: JSONObject) {
        when (val action = command.optString("action", "observe")) {
            "observe", "diagnose" -> Unit
            "launch" -> launchTarget(command.optString("package", prefs.allowedPackage))
            "tap" -> executeTap(command)
            "tap_text" -> executeTapText(command, numeric = false)
            "tap_number" -> executeTapText(command, numeric = true)
            "swipe" -> executeSwipe(command)
            "back" -> executeBack()
            "wait" -> delay(
                (command.optDouble("seconds", 1.0).coerceIn(0.03, 30.0) * 1_000).roundToLong()
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

    private suspend fun executeTapText(command: JSONObject, numeric: Boolean) {
        val service = requireSafeForeground()
        val observation = lastVision ?: error("No readable observation is available yet")
        val source = (if (numeric) observation.optJSONArray("numbers") else observation.optJSONArray("elements"))
            ?: error("Observation has no OCR elements")
        val wanted = if (numeric) {
            command.getInt("value").toString()
        } else {
            command.getString("text").trim()
        }
        val matchMode = command.optString("match", "exact").lowercase()
        val occurrence = command.optInt("occurrence", 0).coerceAtLeast(0)
        val matches = mutableListOf<JSONObject>()

        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val candidate = item.optString("text").trim()
            val matched = when (matchMode) {
                "contains" -> candidate.contains(wanted, ignoreCase = true)
                "prefix" -> candidate.startsWith(wanted, ignoreCase = true)
                else -> candidate.equals(wanted, ignoreCase = true)
            }
            if (matched) matches += item
        }

        val selected = matches.getOrNull(occurrence)
            ?: error("OCR target not found: $wanted occurrence $occurrence")
        val center = selected.optJSONObject("center") ?: error("OCR target has no coordinates")
        val x = center.getDouble("x").toFloat()
        val y = center.getDouble("y").toFloat()
        require(CommandPolicy.isPointInside(x, y, screenWidth, screenHeight)) {
            "OCR target is outside screen bounds"
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
        check(service.swipe(x1, y1, x2, y2, command.optLong("duration_ms", 300))) {
            "Swipe gesture failed"
        }
    }

    private fun executeBack() {
        check(requireSafeForeground().back()) { "Back action failed" }
    }

    private suspend fun executeBatch(actions: JSONArray) {
        val limit = minOf(actions.length(), MAX_BATCH_ACTIONS)
        for (index in 0 until limit) {
            val item = actions.getJSONObject(index)
            when (item.optString("action")) {
                "tap" -> executeTap(item)
                "tap_text" -> executeTapText(item, numeric = false)
                "tap_number" -> executeTapText(item, numeric = true)
                "swipe" -> executeSwipe(item)
                "back" -> executeBack()
                "wait" -> delay(
                    (item.optDouble("seconds", 0.15).coerceIn(0.03, 10.0) * 1_000).roundToLong()
                )
                else -> error("Unsupported batch action at index $index")
            }
            val afterMs = item.optLong("after_ms", DEFAULT_BATCH_GAP_MS).coerceIn(0L, 2_000L)
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
            publishRedactedObservation(frameClient, resolution, force, nowMs)
            return@withLock
        }

        val captured = try {
            captureFrame()
        } catch (error: Throwable) {
            lastCaptureOk = false
            lastCaptureError = error.message ?: error.javaClass.simpleName
            throw error
        }

        try {
            val hash = sha256(captured.jpeg)
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
            val capturedAt = Instant.now().toString()
            val vision = ScreenAnalyzer.analyze(
                bitmap = captured.bitmap,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                frameId = frameId,
                capturedAt = capturedAt,
                foregroundPackage = foreground
            )
                .put("session_id", sessionId)
                .put("allowed_package", prefs.allowedPackage)
                .put("jpeg_sha256", hash)
                .put("redacted", false)

            lastVision = vision
            lastVisionOk = vision.optBoolean("ocr_ok", false)
            lastVisionError = vision.optString("ocr_error").takeIf { it.isNotBlank() && it != "null" }
            lastOcrElementCount = vision.optInt("element_count", 0)
            lastNumberCount = vision.optInt("number_count", 0)
            lastAccessibilityNodeCount = vision.optJSONObject("accessibility")?.optInt("node_count", 0) ?: 0

            frameClient.putText(
                VISION_PATH,
                vision.toString(2),
                "Update machine observation ${sessionId.take(8)} #$frameId"
            )

            val encoded = Base64.encodeToString(captured.jpeg, Base64.NO_WRAP)
            val chunks = JSONArray()
            encoded.chunked(FRAME_CHUNK_CHARS).forEach { chunk -> chunks.put(chunk) }
            lastFrameChunkCount = chunks.length()

            val envelope = JSONObject()
                .put("protocol_version", 5)
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("session_id", sessionId)
                .put("frame_id", frameId)
                .put("captured_at", capturedAt)
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
                .put("jpeg_byte_count", captured.jpeg.size)
                .put("chunk_encoding", "base64-no-wrap")
                .put("chunk_chars", FRAME_CHUNK_CHARS)
                .put("chunk_count", chunks.length())
                .put("vision_path", VISION_PATH)
                .put(
                    "vision_summary",
                    JSONObject()
                        .put("ocr_ok", lastVisionOk)
                        .put("ocr_error", lastVisionError ?: JSONObject.NULL)
                        .put("element_count", lastOcrElementCount)
                        .put("number_count", lastNumberCount)
                        .put("accessibility_node_count", lastAccessibilityNodeCount)
                )
                .put("jpeg_base64_chunks", chunks)

            frameClient.putText(
                FRAME_PATH,
                envelope.toString(2),
                "Update chunked frame ${sessionId.take(8)} #$frameId"
            )
            lastFrameSignature = signature
            lastFramePublishedAt = nowMs
        } finally {
            captured.bitmap.recycle()
        }
    }

    private suspend fun publishRedactedObservation(
        frameClient: GitHubClient,
        resolution: ForegroundResolution,
        force: Boolean,
        nowMs: Long
    ) {
        val foreground = resolution.packageName ?: "unknown"
        val signature = "redacted|$foreground|${resolution.source}"
        lastCaptureOk = false
        lastCaptureError = null
        lastVisionOk = false
        lastVisionError = null
        lastOcrElementCount = 0
        lastNumberCount = 0
        lastAccessibilityNodeCount = 0
        lastFrameChunkCount = 0
        lastVision = null

        if (!force &&
            signature == lastFrameSignature &&
            nowMs - lastFramePublishedAt < FRAME_HEARTBEAT_MS
        ) {
            return
        }

        frameId += 1
        val capturedAt = Instant.now().toString()
        val vision = JSONObject()
            .put("protocol_version", 5)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("session_id", sessionId)
            .put("frame_id", frameId)
            .put("captured_at", capturedAt)
            .put("foreground_package", foreground)
            .put("allowed_package", prefs.allowedPackage)
            .put("redacted", true)
            .put("ocr_ok", false)
            .put("ocr_error", JSONObject.NULL)
            .put("numbers", JSONArray())
            .put("elements", JSONArray())
            .put("accessibility", JSONObject().put("available", false).put("nodes", JSONArray()))

        frameClient.putText(
            VISION_PATH,
            vision.toString(2),
            "Update redacted observation ${sessionId.take(8)}"
        )

        val envelope = JSONObject()
            .put("protocol_version", 5)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("session_id", sessionId)
            .put("frame_id", frameId)
            .put("captured_at", capturedAt)
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
            .put("jpeg_byte_count", 0)
            .put("chunk_count", 0)
            .put("vision_path", VISION_PATH)
            .put("jpeg_base64_chunks", JSONArray())

        frameClient.putText(
            FRAME_PATH,
            envelope.toString(2),
            "Update redacted frame ${sessionId.take(8)}"
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
            lastVisionOk,
            lastVisionError,
            lastOcrElementCount,
            lastNumberCount,
            lastAccessibilityNodeCount,
            lastFrameChunkCount,
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
            .put("ocr_ok", lastVisionOk)
            .put("machine_observation_ready", allowed && (lastVisionOk || lastAccessibilityNodeCount > 0))

        val state = JSONObject()
            .put("protocol_version", 5)
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
            .put("vision_path", VISION_PATH)
            .put("control_branch", CONTROL_BRANCH)
            .put("state_branch", STATE_BRANCH)
            .put("frame_branch", FRAME_BRANCH)
            .put("command_poll_ms", COMMAND_POLL_MS)
            .put("background_observation_ms", BACKGROUND_FRAME_POLL_MS)
            .put("last_seq", prefs.lastSequence)
            .put("last_error", lastError ?: JSONObject.NULL)
            .put("capture_error", lastCaptureError ?: JSONObject.NULL)
            .put("vision_error", lastVisionError ?: JSONObject.NULL)
            .put("ocr_element_count", lastOcrElementCount)
            .put("number_count", lastNumberCount)
            .put("accessibility_node_count", lastAccessibilityNodeCount)
            .put("frame_chunk_count", lastFrameChunkCount)
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
                        "tap_text",
                        "tap_number",
                        "swipe",
                        "back",
                        "wait",
                        "batch",
                        "panic",
                        "offline_ocr_observation",
                        "accessibility_tree",
                        "chunked_frame_json",
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

    private suspend fun captureFrame(): CapturedFrame {
        check(projection != null && virtualDisplay != null && imageReader != null) {
            "MediaProjection is not active"
        }
        val deferred = CompletableDeferred<CapturedFrame>()
        pendingCapture.getAndSet(deferred)?.cancel()
        return withTimeout(CAPTURE_TIMEOUT_MS) { deferred.await() }
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
        .setContentTitle("ChatGPT Device Lab v2.4.1 active")
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

    private data class CapturedFrame(val jpeg: ByteArray, val bitmap: Bitmap)

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
        private const val VISION_PATH = "state/vision.json"

        private const val COMMAND_POLL_MS = 950L
        private const val BACKGROUND_FRAME_POLL_MS = 1_600L
        private const val MIN_LOOP_DELAY_MS = 60L
        private const val FRAME_HEARTBEAT_MS = 10_000L
        private const val STATUS_HEARTBEAT_MS = 15_000L
        private const val CAPTURE_TIMEOUT_MS = 4_500L

        private const val FRAME_WIDTH = 640
        private const val FRAME_JPEG_QUALITY = 47
        private const val FRAME_CHUNK_CHARS = 5_000
        private const val MAX_BATCH_ACTIONS = 60
        private const val DEFAULT_BATCH_GAP_MS = 55L
    }
}
