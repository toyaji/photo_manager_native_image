package com.fluttercandies.example

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluttercandies.photo_manager_native_image.DecodeStep
import com.fluttercandies.photo_manager_native_image.NativeImageDecoder
import com.fluttercandies.photo_manager_native_image.ThumbnailMath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Video thumbnails reach the requested shorter side and show the system thumbnail's frame. */
@RunWith(AndroidJUnit4::class)
class VideoFrameSizeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val decoder = NativeImageDecoder(context)
    private val created = mutableListOf<Uri>()

    @After
    fun tearDown() {
        created.forEach { context.contentResolver.delete(it, null, null) }
    }

    /** Inserts through MediaStore and waits for the scan, so WIDTH and HEIGHT are recorded. */
    private fun video(asset: String): String {
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "pmni_${System.nanoTime()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            },
        )!!
        created.add(uri)
        resolver.openOutputStream(uri)!!.use { out ->
            instrumentation.context.assets.open(asset).use { it.copyTo(out) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        @Suppress("DEPRECATION")
        val path = resolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)!!.use {
            it.moveToFirst()
            it.getString(0)
        }
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> latch.countDown() }
        assertTrue("media scan timed out", latch.await(20, TimeUnit.SECONDS))
        return ContentUris.parseId(uri).toString()
    }

    private fun assertSize(id: String, size: Int, landscape: Boolean) {
        val bitmap = decoder.decodeBitmap(id, size, size, isVideo = true, originalDecodeAbove = 256)
        val d = "${bitmap.width}x${bitmap.height} for $size"
        assertEquals(d, size, minOf(bitmap.width, bitmap.height))
        assertEquals(d, landscape, bitmap.width > bitmap.height)
    }

    @Test
    fun landscapeVideoReachesTheShorterSide() {
        val id = video("wide_1080p.mp4")
        listOf(96, 300, 384, 512, 720, 1080).forEach { assertSize(id, it, landscape = true) }
    }

    @Test
    fun rotatedVideoReachesTheShorterSide() {
        val id = video("wide_1080p_rot90.mp4")
        listOf(300, 512, 1080).forEach { assertSize(id, it, landscape = false) }
    }

    /**
     * `middle_differs.mp4` opens on a busy pattern (its largest early sync frame, the
     * retriever's representative) and turns blue before its middle sync frame, so a
     * decoder that picks its frame differently from the system thumbnail fails here.
     */
    @Test
    fun everySizeShowsTheSystemThumbnailsFrame() {
        val id = video("middle_differs.mp4")
        val thumbnail = greySquare(systemThumbnail(id.toLong()))
        listOf(96, 300, 400, 720, 1080).forEach { size ->
            val bitmap = decoder.decodeBitmap(id, size, size, isVideo = true, originalDecodeAbove = 256)
            val d = "${bitmap.width}x${bitmap.height} for $size"
            assertEquals(d, size, minOf(bitmap.width, bitmap.height))
            assertTrue("$d is not the system thumbnail's frame", ThumbnailMath.sameFrame(thumbnail, greySquare(bitmap)))
        }
    }

    /** MediaStore records the size of a 4096x2160 video, so its frame goes to the one-at-a-time stage. */
    @Test
    fun aVideoAboveUhdIsDecodedOnTheFrameStage() {
        val id = video("dci_4k.mp4")
        val step = decoder.decode(id, 1080, 1080, isVideo = true, originalDecodeAbove = 256)
        assertTrue("expected a frame step, got $step", step is DecodeStep.NeedsFrame)
        step as DecodeStep.NeedsFrame
        assertTrue("4096x2160 is not marked large", step.large)
        if (!decodesAvc(4096, 2160)) {
            step.abandon()
            assumeTrue("no H.264 decoder for 4096x2160 on this device", false)
        }
        val bitmap = step.finish()
        assertEquals("${bitmap.width}x${bitmap.height}", 1080, minOf(bitmap.width, bitmap.height))
    }

    /**
     * A large frame that waits keeps only a comparison sample of the system thumbnail, so this
     * checks it still lands on the thumbnail's frame (`middle_differs.mp4` at 4096x2160).
     */
    @Test
    fun aLargeVideoStillShowsTheSystemThumbnailsFrame() {
        assumeTrue("no H.264 decoder for 4096x2160 on this device", decodesAvc(4096, 2160))
        val id = video("dci_4k_middle_differs.mp4")
        val thumbnail = greySquare(systemThumbnail(id.toLong()))
        val step = decoder.decode(id, 720, 720, isVideo = true, originalDecodeAbove = 256)
        assumeTrue("the system thumbnail already covers 720 here", step is DecodeStep.NeedsFrame)
        step as DecodeStep.NeedsFrame
        assertTrue(step.large)
        val bitmap = step.finish()
        assertEquals("${bitmap.width}x${bitmap.height}", 720, minOf(bitmap.width, bitmap.height))
        assertTrue("not the system thumbnail's frame", ThumbnailMath.sameFrame(thumbnail, greySquare(bitmap)))
    }

    @Test
    fun aFullHdVideoStaysInTheParallelPool() {
        val id = video("wide_1080p.mp4")
        val step = decoder.decode(id, 1080, 1080, isVideo = true, originalDecodeAbove = 256)
        if (step is DecodeStep.NeedsFrame) {
            assertFalse("1920x1080 is marked large", step.large)
            step.abandon()
        }
    }

    /** Older emulators have no software decoder for 4096x2160, so neither a frame nor a thumbnail comes out. */
    private fun decodesAvc(width: Int, height: Int): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities?.isSizeSupported(width, height) == true
        }

    private fun systemThumbnail(id: Long): Bitmap {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        return context.contentResolver.loadThumbnail(uri, Size(128, 128), null)
    }

    private fun greySquare(bitmap: Bitmap): IntArray {
        val side = ThumbnailMath.FRAME_COMPARE_SIDE
        val small = Bitmap.createScaledBitmap(bitmap, side, side, true)
        val pixels = IntArray(side * side)
        small.getPixels(pixels, 0, side, 0, 0, side, side)
        return ThumbnailMath.grey(pixels)
    }
}
