package com.fluttercandies.photo_manager_native_image

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Point
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.FileNotFoundException
import java.nio.ByteBuffer

private val SRGB: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

class NativeImageException(val code: String, message: String) : Exception(message)

/** What [NativeImageDecoder.decode] produced, or what is left to do. */
sealed interface DecodeStep {
    class Done(val bitmap: Bitmap) : DecodeStep

    /**
     * The picture has to come from a video frame. A retriever decodes the whole frame at the
     * video's own size; when [large], the caller runs [finish] where such decodes are kept one
     * at a time. [abandon] releases what was decoded so far when [finish] will not run.
     */
    class NeedsFrame(val large: Boolean, val finish: () -> Bitmap, val abandon: () -> Unit) : DecodeStep
}

/** Produces an upright `ARGB_8888` bitmap for a MediaStore id. */
class NativeImageDecoder(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun decode(assetId: String, width: Int, height: Int, isVideo: Boolean, originalDecodeAbove: Int): DecodeStep {
        val id = assetId.toLongOrNull()
            ?: throw NativeImageException("not_found", "Not a MediaStore id: $assetId")
        val uri = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id,
        )
        val step = mapErrors { decodeQ(uri, width, height, isVideo, originalDecodeAbove) }
        return when (step) {
            is DecodeStep.Done -> DecodeStep.Done(fit(step.bitmap, width, height))
            is DecodeStep.NeedsFrame -> DecodeStep.NeedsFrame(
                large = step.large,
                finish = { fit(mapErrors(step.finish), width, height) },
                abandon = step.abandon,
            )
        }
    }

    private fun fit(bitmap: Bitmap, width: Int, height: Int): Bitmap =
        ensureArgb8888(downscale(bitmap, width, height))

    private inline fun <T> mapErrors(block: () -> T): T = try {
        block()
    } catch (e: FileNotFoundException) {
        throw NativeImageException("not_found", e.message ?: "Asset not found")
    } catch (e: NativeImageException) {
        throw e
    } catch (e: Exception) {
        throw NativeImageException("decode_failed", e.toString())
    }

    // MARK: API 29+

    private fun decodeQ(uri: Uri, width: Int, height: Int, isVideo: Boolean, originalDecodeAbove: Int): DecodeStep {
        var thumbnail: Bitmap? = null
        if (ThumbnailMath.triesSystemThumbnail(width, height, isVideo, originalDecodeAbove)) {
            // A cached system thumbnail can be unreadable; the source still is.
            thumbnail = try {
                decodeSystemThumbnail(uri, width, height)
            } catch (e: ImageDecoder.DecodeException) {
                null
            }
            if (thumbnail != null && ThumbnailMath.coversTarget(thumbnail.width, thumbnail.height, width, height)) {
                return DecodeStep.Done(thumbnail)
            }
        }
        if (!isVideo) {
            thumbnail?.recycle()
            return DecodeStep.Done(decodeOriginal(uri, width, height))
        }
        val large = isLargeVideo(uri)
        if (!large || thumbnail == null) {
            return DecodeStep.NeedsFrame(
                large = large,
                finish = { frameOrThumbnail(uri, width, height, thumbnail) },
                abandon = { thumbnail?.recycle() },
            )
        }
        // A large frame may wait behind others, so only the comparison sample is kept meanwhile.
        val thumbnailGrey = greySquare(thumbnail)
        thumbnail.recycle()
        return DecodeStep.NeedsFrame(
            large = true,
            finish = { frameOrRereadThumbnail(uri, width, height, thumbnailGrey) },
            abandon = {},
        )
    }

    /** [frameOrThumbnail] for a thumbnail that was let go: it is read again only if the frame differs. */
    private fun frameOrRereadThumbnail(uri: Uri, width: Int, height: Int, thumbnailGrey: IntArray): Bitmap {
        val frame = decodeVideoFrame(uri, width, height)
        if (ThumbnailMath.sameFrame(thumbnailGrey, greySquare(frame))) return frame
        val thumbnail = try {
            decodeSystemThumbnail(uri, width, height)
        } catch (e: ImageDecoder.DecodeException) {
            return frame
        }
        frame.recycle()
        return thumbnail
    }

    private fun frameOrThumbnail(uri: Uri, width: Int, height: Int, thumbnail: Bitmap?): Bitmap {
        val frame = try {
            decodeVideoFrame(uri, width, height)
        } catch (e: Exception) {
            thumbnail?.recycle()
            throw e
        }
        if (thumbnail == null) return frame
        // The frame follows the system thumbnail's rule, but a thumbnail made another way would
        // swap the picture between sizes; a soft picture is the lesser defect.
        if (sameFrame(thumbnail, frame)) {
            thumbnail.recycle()
            return frame
        }
        frame.recycle()
        return thumbnail
    }

    private fun sameFrame(a: Bitmap, b: Bitmap): Boolean =
        ThumbnailMath.sameFrame(greySquare(a), greySquare(b))

    private fun greySquare(bitmap: Bitmap): IntArray {
        val side = ThumbnailMath.FRAME_COMPARE_SIDE
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val small = Bitmap.createScaledBitmap(source, side, side, true)
        val pixels = IntArray(side * side)
        small.getPixels(pixels, 0, side, 0, 0, side, side)
        if (small !== source) small.recycle()
        if (source !== bitmap) source.recycle()
        return ThumbnailMath.grey(pixels)
    }

    /**
     * `ContentResolver.loadThumbnail` without its fit-inside-the-box sampling,
     * decoded straight to the target size in sRGB.
     */
    private fun decodeSystemThumbnail(uri: Uri, width: Int, height: Int): Bitmap {
        val opts = Bundle().apply { putParcelable(ContentResolver.EXTRA_SIZE, Point(width, height)) }
        var orientation = 0
        val source = ImageDecoder.createSource {
            val afd = resolver.openTypedAssetFileDescriptor(uri, "image/*", opts, null)
                ?: throw FileNotFoundException(uri.toString())
            orientation = afd.extras?.getInt(DocumentsContract.EXTRA_ORIENTATION, 0) ?: 0
            afd
        }
        val bitmap = decodeToTarget(source, width, height)
        if (orientation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(orientation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun decodeOriginal(uri: Uri, width: Int, height: Int): Bitmap =
        decodeToTarget(ImageDecoder.createSource(resolver, uri), width, height)

    /** Flutter's raw image descriptor reads pixels as sRGB, so wide-gamut sources are converted. */
    private fun decodeToTarget(source: ImageDecoder.Source, width: Int, height: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            decoder.setTargetColorSpace(SRGB)
            ThumbnailMath.scaledSize(info.size.width, info.size.height, width, height)
                ?.let { (w, h) -> decoder.setTargetSize(w, h) }
        }

    /**
     * The picture the system thumbnail shows, sized from the video's own dimensions so the
     * shorter side meets the target: the cover art if there is one, else the sync frame at
     * half the duration, as `ThumbnailUtils` picks them. Retriever frames are upright.
     */
    private fun decodeVideoFrame(uri: Uri, width: Int, height: Int): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture?.let { return decodeToTarget(ImageDecoder.createSource(ByteBuffer.wrap(it)), width, height) }
            val timeUs = ThumbnailMath.videoFrameTimeUs(
                Build.VERSION.SDK_INT,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
            val box = ThumbnailMath.videoFrameBox(
                retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                width,
                height,
            )
            val frame = if (box != null) {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, box.first, box.second)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            return frame ?: throw NativeImageException("decode_failed", "No frame for $uri")
        } finally {
            retriever.release()
        }
    }

    /** From MediaStore's recorded size. An unknown size, or a lookup that fails, counts as small. */
    private fun isLargeVideo(uri: Uri): Boolean {
        val columns = arrayOf(MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
        return try {
            resolver.query(uri, columns, null, null, null)?.use { c ->
                c.moveToFirst() && ThumbnailMath.isLargeFrame(c.getInt(0), c.getInt(1))
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun MediaMetadataRetriever.intMetadata(key: Int): Int =
        extractMetadata(key)?.toIntOrNull() ?: 0

    /** `(width, height, rotationDegrees)` of an image as MediaStore reports it. */
    @SuppressLint("InlinedApi")
    @Suppress("DEPRECATION")
    internal fun imageMetadata(uri: Uri): Triple<Int, Int, Int> {
        val projection = arrayOf(
            MediaStore.Images.ImageColumns.WIDTH,
            MediaStore.Images.ImageColumns.HEIGHT,
            MediaStore.Images.ImageColumns.ORIENTATION,
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return Triple(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2))
        }
        return Triple(0, 0, 0)
    }

    // MARK: Shared

    private fun downscale(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val target = ThumbnailMath.scaledSize(bitmap.width, bitmap.height, width, height) ?: return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, target.first, target.second, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw NativeImageException("decode_failed", "Could not convert to ARGB_8888")
        bitmap.recycle()
        return copy
    }
}
