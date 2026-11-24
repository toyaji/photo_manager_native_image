package com.fluttercandies.photo_manager_native_image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pure sizing and rotation rules shared by the decoders; unit-tested. */
object ThumbnailMath {
    /**
     * Largest video size for which the system thumbnail is tried first. MediaStore keeps one
     * thumbnail per video, fitted in half the screen's shorter side, so larger requests skip it.
     */
    const val SYSTEM_VIDEO_THUMBNAIL_MAX = 768

    /** Above this many pixels (3840 × 2160) a video frame decode holds hundreds of MB. */
    const val LARGE_FRAME_PIXELS = 3840L * 2160L

    /** Side of the grey squares two pictures are compared at. */
    const val FRAME_COMPARE_SIDE = 32

    /** Mean grey difference (0–255) below which two pictures show the same frame. */
    const val SAME_FRAME = 8.0

    /** Decoded pixels are capped at this many target squares, so panoramas stay bounded. */
    const val MAX_PIXELS_IN_TARGET_SQUARES = 4

    /**
     * Size that scales `width x height` so its shorter side equals
     * `min(targetWidth, targetHeight)`, then shrinks further until the area is
     * at most [MAX_PIXELS_IN_TARGET_SQUARES] target squares. Never upscales;
     * `null` when the image already fits.
     */
    fun scaledSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val targetShorter = min(targetWidth, targetHeight).toDouble()
        val shorter = min(width, height)
        var scale = if (shorter > targetShorter) targetShorter / shorter else 1.0
        val maxPixels = MAX_PIXELS_IN_TARGET_SQUARES * targetShorter * targetShorter
        val pixels = width * scale * height * scale
        if (pixels > maxPixels) scale *= sqrt(maxPixels / pixels)
        if (scale >= 1.0) return null
        return Pair(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
    }

    /**
     * Box to pass to `MediaMetadataRetriever.getScaledFrameAtTime` so the
     * frame, which is fitted inside the box, comes out at [scaledSize] of the
     * upright video. The longer side gets one extra pixel so rounding never
     * makes the box limit the shorter side.
     */
    fun videoFrameBox(
        videoWidth: Int,
        videoHeight: Int,
        rotationDegrees: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Pair<Int, Int>? {
        if (videoWidth <= 0 || videoHeight <= 0) return null
        val quarterTurn = (((rotationDegrees % 360) + 360) % 360) % 180 == 90
        val width = if (quarterTurn) videoHeight else videoWidth
        val height = if (quarterTurn) videoWidth else videoHeight
        val (w, h) = scaledSize(width, height, targetWidth, targetHeight) ?: Pair(width, height)
        return if (w >= h) Pair(w + 1, h) else Pair(w, h + 1)
    }

    /**
     * Whether the MediaStore system thumbnail is tried before decoding the source. Images use
     * it only up to [originalDecodeAbove], the caller's choice between speed and sharpness.
     */
    fun triesSystemThumbnail(targetWidth: Int, targetHeight: Int, isVideo: Boolean, originalDecodeAbove: Int): Boolean =
        max(targetWidth, targetHeight) <= if (isVideo) SYSTEM_VIDEO_THUMBNAIL_MAX else originalDecodeAbove

    /**
     * Time of the frame a video thumbnail shows, as `ThumbnailUtils.createVideoThumbnail` picks
     * it: the sync frame closest to half the duration. Android 10 passes half the duration in
     * milliseconds where microseconds belong, so its thumbnail is the sync frame near the start;
     * Android 11 fixed the unit. A video without a duration gets the retriever's representative
     * frame, which `-1` selects.
     */
    fun videoFrameTimeUs(sdkInt: Int, durationMs: Long): Long = when {
        durationMs <= 0 -> -1
        sdkInt == 29 -> durationMs / 2
        else -> durationMs * 1000 / 2
    }

    /** Luma of ARGB pixels. */
    fun grey(pixels: IntArray): IntArray = IntArray(pixels.size) { i ->
        val p = pixels[i]
        ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }

    /** Whether two grey squares of equal size show the same frame. */
    fun sameFrame(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size || a.isEmpty()) return false
        var sum = 0L
        for (i in a.indices) sum += kotlin.math.abs(a[i] - b[i])
        return sum.toDouble() / a.size < SAME_FRAME
    }

    /** Whether a `width x height` video frame is large enough to be decoded one at a time. */
    fun isLargeFrame(width: Int, height: Int): Boolean =
        width.toLong() * height.toLong() > LARGE_FRAME_PIXELS

    /** Whether a decoded `width x height` reaches the requested shorter side. */
    fun coversTarget(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Boolean =
        min(width, height) >= min(targetWidth, targetHeight)

    /**
     * Power-of-two `BitmapFactory.Options.inSampleSize` that keeps the
     * decoded shorter side at or above `targetShorter`.
     */
    fun sampleSize(width: Int, height: Int, targetShorter: Int): Int {
        var sample = 1
        val shorter = min(width, height)
        while (shorter / (sample * 2) >= targetShorter) {
            sample *= 2
        }
        return sample
    }

    /**
     * The nine `android.graphics.Matrix` values that rotate a `bitmapWidth x
     * bitmapHeight` bitmap by [rotation] degrees (multiples of 90) and scale it
     * to fill `viewWidth x viewHeight`, centred (aspect fill).
     */
    fun coverMatrixValues(
        bitmapWidth: Int,
        bitmapHeight: Int,
        rotation: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): FloatArray {
        val turns = (((rotation / 90) % 4) + 4) % 4
        val cos = intArrayOf(1, 0, -1, 0)[turns]
        val sin = intArrayOf(0, 1, 0, -1)[turns]
        val quarter = turns % 2 == 1
        val uprightWidth = if (quarter) bitmapHeight else bitmapWidth
        val uprightHeight = if (quarter) bitmapWidth else bitmapHeight
        if (uprightWidth <= 0 || uprightHeight <= 0) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val scale = max(viewWidth.toFloat() / uprightWidth, viewHeight.toFloat() / uprightHeight)
        val a = scale * cos
        val b = -scale * sin
        val c = scale * sin
        val d = scale * cos
        val halfW = bitmapWidth / 2f
        val halfH = bitmapHeight / 2f
        val tx = viewWidth / 2f - (a * halfW + b * halfH)
        val ty = viewHeight / 2f - (c * halfW + d * halfH)
        return floatArrayOf(a, b, tx, c, d, ty, 0f, 0f, 1f)
    }
}
