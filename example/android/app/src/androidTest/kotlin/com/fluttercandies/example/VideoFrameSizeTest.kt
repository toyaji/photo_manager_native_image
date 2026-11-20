package com.fluttercandies.example

import android.content.ContentUris
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluttercandies.photo_manager_native_image.NativeImageDecoder
import com.fluttercandies.photo_manager_native_image.ThumbnailMath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Video thumbnails reach the requested shorter side and show the system thumbnail's frame. */
@RunWith(AndroidJUnit4::class)
class VideoFrameSizeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val decoder = NativeImageDecoder(context)
    private val created = mutableListOf<Pair<File, Uri>>()

    @After
    fun tearDown() {
        created.forEach { (file, uri) ->
            context.contentResolver.delete(uri, null, null)
            file.delete()
        }
    }

    private fun video(asset: String): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        dir.mkdirs()
        val file = File(dir, "pmni_${System.nanoTime()}.mp4")
        instrumentation.context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val latch = CountDownLatch(1)
        var uri: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, scanned ->
            uri = scanned
            latch.countDown()
        }
        assertTrue("media scan timed out", latch.await(20, TimeUnit.SECONDS))
        created.add(file to uri!!)
        return ContentUris.parseId(uri).toString()
    }

    private fun assertSize(id: String, size: Int, landscape: Boolean) {
        val bitmap = decoder.decode(id, size, size, isVideo = true, originalDecodeAbove = 256)
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
            val bitmap = decoder.decode(id, size, size, isVideo = true, originalDecodeAbove = 256)
            val d = "${bitmap.width}x${bitmap.height} for $size"
            assertEquals(d, size, minOf(bitmap.width, bitmap.height))
            assertTrue("$d is not the system thumbnail's frame", ThumbnailMath.sameFrame(thumbnail, greySquare(bitmap)))
        }
    }

    @Suppress("DEPRECATION")
    private fun systemThumbnail(id: Long): Bitmap {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(128, 128), null)
        } else {
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null,
            )!!
        }
    }

    private fun greySquare(bitmap: Bitmap): IntArray {
        val side = ThumbnailMath.FRAME_COMPARE_SIDE
        val small = Bitmap.createScaledBitmap(bitmap, side, side, true)
        val pixels = IntArray(side * side)
        small.getPixels(pixels, 0, side, 0, 0, side, side)
        return ThumbnailMath.grey(pixels)
    }
}
