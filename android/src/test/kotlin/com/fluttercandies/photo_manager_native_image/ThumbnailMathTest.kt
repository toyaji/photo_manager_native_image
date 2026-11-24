package com.fluttercandies.photo_manager_native_image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailMathTest {
    @Test
    fun scaledSizeMatchesShorterSideWithoutUpscaling() {
        assertEquals(Pair(427, 320), ThumbnailMath.scaledSize(640, 480, 320, 320))
        assertEquals(Pair(320, 427), ThumbnailMath.scaledSize(480, 640, 320, 320))
        assertNull(ThumbnailMath.scaledSize(300, 200, 320, 320))
        assertNull(ThumbnailMath.scaledSize(320, 320, 320, 320))
    }

    @Test
    fun scaledSizeCapsPanoramasAtFourTargetSquares() {
        assertEquals(Pair(5793, 724), ThumbnailMath.scaledSize(8000, 1000, 1024, 1024))
        assertEquals(Pair(640, 160), ThumbnailMath.scaledSize(4000, 1000, 160, 160))
        assertNull(ThumbnailMath.scaledSize(1280, 320, 320, 320))
    }

    @Test
    fun imagesUseTheSystemThumbnailUpToTheRequestedSize() {
        assertTrue(ThumbnailMath.triesSystemThumbnail(256, 256, isVideo = false, originalDecodeAbove = 256))
        assertFalse(ThumbnailMath.triesSystemThumbnail(257, 257, isVideo = false, originalDecodeAbove = 256))
        assertTrue(ThumbnailMath.triesSystemThumbnail(400, 400, isVideo = false, originalDecodeAbove = 512))
        assertFalse(ThumbnailMath.triesSystemThumbnail(1, 1, isVideo = false, originalDecodeAbove = 0))
    }

    @Test
    fun videosUseTheSystemThumbnailUpToItsLargerLimit() {
        assertTrue(ThumbnailMath.triesSystemThumbnail(720, 720, isVideo = true, originalDecodeAbove = 0))
        assertTrue(ThumbnailMath.triesSystemThumbnail(768, 768, isVideo = true, originalDecodeAbove = 0))
        assertFalse(ThumbnailMath.triesSystemThumbnail(769, 769, isVideo = true, originalDecodeAbove = 1024))
    }

    @Test
    fun videoFramesFollowTheSystemThumbnailRule() {
        assertEquals(5_000_000L, ThumbnailMath.videoFrameTimeUs(29, 10_000L))
        assertEquals(1_500L, ThumbnailMath.videoFrameTimeUs(34, 3L))
        assertEquals(-1L, ThumbnailMath.videoFrameTimeUs(28, 10_000L))
        assertEquals(-1L, ThumbnailMath.videoFrameTimeUs(34, 0L))
    }

    @Test
    fun sameFrameToleratesRecompressionButNotAnotherPicture() {
        val side = ThumbnailMath.FRAME_COMPARE_SIDE * ThumbnailMath.FRAME_COMPARE_SIDE
        val red = ThumbnailMath.grey(IntArray(side) { 0xFFFF0000.toInt() })
        val nearRed = ThumbnailMath.grey(IntArray(side) { 0xFFF00808.toInt() })
        val blue = ThumbnailMath.grey(IntArray(side) { 0xFF0000FF.toInt() })
        assertTrue(ThumbnailMath.sameFrame(red, nearRed))
        assertFalse(ThumbnailMath.sameFrame(red, blue))
        assertFalse(ThumbnailMath.sameFrame(red, red.copyOf(side - 1)))
    }

    @Test
    fun sampleSizeKeepsShorterSideAtLeastTarget() {
        assertEquals(1, ThumbnailMath.sampleSize(400, 300, 320))
        assertEquals(2, ThumbnailMath.sampleSize(4000, 700, 320))
        assertEquals(8, ThumbnailMath.sampleSize(4000, 3000, 320))
        assertEquals(16, ThumbnailMath.sampleSize(8000, 6000, 320))
    }

    @Test
    fun rotationIsAppliedToUnrotatedThumbnails() {
        // Landscape source, landscape thumbnail, EXIF 90: still needs rotating.
        assertEquals(90, ThumbnailMath.rotationToApply(90, 4000, 3000, 512, 384))
        assertEquals(270, ThumbnailMath.rotationToApply(270, 4000, 3000, 512, 384))
        assertEquals(180, ThumbnailMath.rotationToApply(180, 4000, 3000, 512, 384))
        assertEquals(0, ThumbnailMath.rotationToApply(0, 4000, 3000, 512, 384))
    }

    @Test
    fun rotationIsSkippedWhenThumbnailIsAlreadyUpright() {
        // Landscape source, portrait thumbnail: the framework already rotated.
        assertEquals(0, ThumbnailMath.rotationToApply(90, 4000, 3000, 384, 512))
        assertEquals(0, ThumbnailMath.rotationToApply(270, 1920, 1080, 270, 480))
    }

    @Test
    fun rotationFallsBackToMetadataForSquareOrUnknownSources() {
        assertEquals(90, ThumbnailMath.rotationToApply(90, 1000, 1000, 100, 100))
        assertEquals(90, ThumbnailMath.rotationToApply(90, 0, 0, 512, 384))
        assertEquals(90, ThumbnailMath.rotationToApply(-270, 4000, 3000, 512, 384))
        assertEquals(0, ThumbnailMath.rotationToApply(45, 4000, 3000, 512, 384))
    }

    @Test
    fun videoFrameBoxReachesTheShorterSide() {
        assertEquals(Pair(911, 512), ThumbnailMath.videoFrameBox(1920, 1080, 0, 512, 512))
        assertEquals(Pair(512, 911), ThumbnailMath.videoFrameBox(1920, 1080, 90, 512, 512))
        assertEquals(Pair(1921, 1080), ThumbnailMath.videoFrameBox(1920, 1080, 0, 1080, 1080))
    }

    @Test
    fun videoFrameBoxNeverUpscales() {
        assertEquals(Pair(641, 360), ThumbnailMath.videoFrameBox(640, 360, 0, 512, 512))
        assertNull(ThumbnailMath.videoFrameBox(0, 0, 0, 512, 512))
    }

    @Test
    fun coversTargetComparesShorterSides() {
        assertTrue(ThumbnailMath.coversTarget(910, 512, 512, 512))
        assertFalse(ThumbnailMath.coversTarget(512, 288, 512, 512))
        assertFalse(ThumbnailMath.coversTarget(540, 304, 384, 384))
    }

    @Test
    fun sampleSizeKeepsHdrDecodeAtOrAboveTarget() {
        assertEquals(1, ThumbnailMath.sampleSize(3000, 4000, 2217))
        assertEquals(2, ThumbnailMath.sampleSize(6120, 8160, 2217))
        assertEquals(1, ThumbnailMath.sampleSize(800, 600, 2217))
    }

    @Test
    fun coverMatrixRotates90AndFills() {
        // 3000x4000 bitmap shown upright (rotation 90) is 4000x3000; the 1080x1440 view
        // is portrait-aspect-matched to 3000x4000 only after rotation, so fill scale is
        // max(1080/4000, 1440/3000) = 0.48.
        val m = ThumbnailMath.coverMatrixValues(3000, 4000, 90, 1080, 1440)
        assertEquals(9, m.size)
        // Rotation by 90 about the bitmap centre, then scale s, then centre in the view.
        val s = 0.48f
        assertEquals(0f, m[0], 1e-4f)
        assertEquals(-s, m[1], 1e-4f)
        assertEquals(1080f / 2 + s * 4000f / 2, m[2], 1e-3f)
        assertEquals(s, m[3], 1e-4f)
        assertEquals(0f, m[4], 1e-4f)
        assertEquals(1440f / 2 - s * 3000f / 2, m[5], 1e-3f)
        assertEquals(0f, m[6], 1e-4f)
        assertEquals(0f, m[7], 1e-4f)
        assertEquals(1f, m[8], 1e-4f)
    }

    @Test
    fun coverMatrixScalesUprightSizeToFillTheView() {
        // Stored 4000x3000 with rotation 90 is upright 3000x4000: 1080x1440 needs 0.36.
        val m = ThumbnailMath.coverMatrixValues(4000, 3000, 90, 1080, 1440)
        val s = 0.36f
        assertEquals(-s, m[1], 1e-4f)
        assertEquals(s, m[3], 1e-4f)
        assertEquals(1080f / 2 + s * 3000f / 2, m[2], 1e-3f)
        assertEquals(1440f / 2 - s * 4000f / 2, m[5], 1e-3f)
    }

    @Test
    fun onlyFramesAbove4kAreLarge() {
        assertTrue(ThumbnailMath.isLargeFrame(7680, 4320))
        assertTrue(ThumbnailMath.isLargeFrame(4320, 7680))
        assertFalse(ThumbnailMath.isLargeFrame(3840, 2160))
        // DCI 4K has more pixels than UHD, so it counts as large.
        assertTrue(ThumbnailMath.isLargeFrame(4096, 2160))
        assertFalse(ThumbnailMath.isLargeFrame(1920, 1080))
        assertFalse(ThumbnailMath.isLargeFrame(0, 0))
    }
}
