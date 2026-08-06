package com.ttclab.chatbridge

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Converts an allowlisted screenshot into a compact, machine-readable observation.
 * The bundled ML Kit model works offline after installation; no frame is sent to ML Kit servers.
 */
object ScreenAnalyzer {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun analyze(
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        frameId: Long,
        capturedAt: String,
        foregroundPackage: String
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        val accessibility = runCatching {
            BridgeAccessibilityService.instance?.snapshotWindowTree(MAX_ACCESSIBILITY_NODES)
                ?: JSONObject()
                    .put("available", false)
                    .put("node_count", 0)
                    .put("nodes", JSONArray())
        }.getOrElse { error ->
            JSONObject()
                .put("available", false)
                .put("error", error.message ?: error.javaClass.simpleName)
                .put("node_count", 0)
                .put("nodes", JSONArray())
        }

        val base = JSONObject()
            .put("protocol_version", 5)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("frame_id", frameId)
            .put("captured_at", capturedAt)
            .put("foreground_package", foregroundPackage)
            .put("screen_width", screenWidth)
            .put("screen_height", screenHeight)
            .put("image_width", bitmap.width)
            .put("image_height", bitmap.height)
            .put("accessibility", accessibility)

        return try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitValue()
            val blocks = JSONArray()
            val lines = JSONArray()
            val elements = JSONArray()
            val numbers = JSONArray()

            for (block in result.textBlocks) {
                blocks.put(textItem(block.text, block.boundingBox, bitmap, screenWidth, screenHeight))
                for (line in block.lines) {
                    lines.put(textItem(line.text, line.boundingBox, bitmap, screenWidth, screenHeight))
                    for (element in line.elements) {
                        val item = textItem(
                            element.text,
                            element.boundingBox,
                            bitmap,
                            screenWidth,
                            screenHeight
                        )
                        elements.put(item)
                        parseNumber(element.text)?.let { value ->
                            numbers.put(JSONObject(item.toString()).put("value", value))
                        }
                        if (elements.length() >= MAX_OCR_ELEMENTS) break
                    }
                    if (elements.length() >= MAX_OCR_ELEMENTS) break
                }
                if (elements.length() >= MAX_OCR_ELEMENTS) break
            }

            base
                .put("ocr_ok", true)
                .put("ocr_error", JSONObject.NULL)
                .put("ocr_latency_ms", System.currentTimeMillis() - startedAt)
                .put("full_text", result.text.take(MAX_FULL_TEXT_CHARS))
                .put("block_count", blocks.length())
                .put("line_count", lines.length())
                .put("element_count", elements.length())
                .put("number_count", numbers.length())
                .put("blocks", blocks)
                .put("lines", lines)
                .put("elements", elements)
                .put("numbers", numbers)
        } catch (error: Throwable) {
            base
                .put("ocr_ok", false)
                .put("ocr_error", error.message ?: error.javaClass.simpleName)
                .put("ocr_latency_ms", System.currentTimeMillis() - startedAt)
                .put("full_text", "")
                .put("block_count", 0)
                .put("line_count", 0)
                .put("element_count", 0)
                .put("number_count", 0)
                .put("blocks", JSONArray())
                .put("lines", JSONArray())
                .put("elements", JSONArray())
                .put("numbers", JSONArray())
        }
    }

    private fun textItem(
        text: String,
        rect: Rect?,
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): JSONObject {
        val item = JSONObject().put("text", text.take(MAX_ITEM_TEXT_CHARS))
        if (rect == null) {
            return item
                .put("image_box", JSONObject.NULL)
                .put("screen_box", JSONObject.NULL)
                .put("center", JSONObject.NULL)
                .put("normalized_center", JSONObject.NULL)
        }

        val scaleX = screenWidth.toDouble() / bitmap.width.coerceAtLeast(1)
        val scaleY = screenHeight.toDouble() / bitmap.height.coerceAtLeast(1)
        val left = (rect.left * scaleX).roundToInt().coerceIn(0, screenWidth)
        val top = (rect.top * scaleY).roundToInt().coerceIn(0, screenHeight)
        val right = (rect.right * scaleX).roundToInt().coerceIn(0, screenWidth)
        val bottom = (rect.bottom * scaleY).roundToInt().coerceIn(0, screenHeight)
        val centerX = ((left + right) / 2.0).roundToInt()
        val centerY = ((top + bottom) / 2.0).roundToInt()

        return item
            .put("image_box", rectJson(rect.left, rect.top, rect.right, rect.bottom))
            .put("screen_box", rectJson(left, top, right, bottom))
            .put("center", pointJson(centerX, centerY))
            .put(
                "normalized_center",
                JSONObject()
                    .put("x", centerX.toDouble() / screenWidth.coerceAtLeast(1))
                    .put("y", centerY.toDouble() / screenHeight.coerceAtLeast(1))
            )
    }

    private fun rectJson(left: Int, top: Int, right: Int, bottom: Int) = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)
        .put("width", (right - left).coerceAtLeast(0))
        .put("height", (bottom - top).coerceAtLeast(0))

    private fun pointJson(x: Int, y: Int) = JSONObject().put("x", x).put("y", y)

    private fun parseNumber(text: String): Int? {
        val normalized = text.trim().replace("O", "0", ignoreCase = true)
        if (!normalized.matches(Regex("^[0-9]{1,3}$"))) return null
        return normalized.toIntOrNull()
    }

    private suspend fun <T> Task<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            continuation.cancel()
        }
    }

    private const val MAX_ACCESSIBILITY_NODES = 350
    private const val MAX_OCR_ELEMENTS = 600
    private const val MAX_FULL_TEXT_CHARS = 20_000
    private const val MAX_ITEM_TEXT_CHARS = 160
}
