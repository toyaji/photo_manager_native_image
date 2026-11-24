package com.fluttercandies.photo_manager_native_image

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodePipelineTest {
    private val registry = RequestRegistry()
    private val decodeQueue = DecodeQueue(1)
    private val frameQueue = DecodeQueue(1)
    private val freed = Collections.synchronizedList(mutableListOf<Long>())
    private val pipeline = DecodePipeline(registry, decodeQueue, frameQueue, { freed.add(it) })

    @After
    fun tearDown() {
        decodeQueue.shutdownNow()
        frameQueue.shutdownNow()
    }

    /** Collects the replies of one request. */
    private class Replies {
        val results = Collections.synchronizedList(mutableListOf<Result<NativeImageReply?>>())
        val first = CountDownLatch(1)

        fun state(id: Long) = RequestState(id) {
            results.add(it)
            first.countDown()
        }

        fun awaitOne(): Result<NativeImageReply?> {
            assertTrue(first.await(5, TimeUnit.SECONDS))
            return results.single()
        }
    }

    private fun buffer(pointer: Long): Result<NativeImageReply?> =
        Result.success(mapOf("pointer" to pointer, "width" to 1L, "height" to 1L, "rowBytes" to 4L))

    /** Waits until everything queued on [queue] before this call has run (one thread, request order). */
    private fun drain(queue: DecodeQueue) {
        val done = CountDownLatch(1)
        queue.execute(Long.MAX_VALUE, "sentinel", Runnable { done.countDown() })
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    /** Occupies [queue]'s only thread until the returned latch is released. */
    private fun block(queue: DecodeQueue): CountDownLatch {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        queue.execute(1L, "busy", Runnable {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        assertTrue(started.await(5, TimeUnit.SECONDS))
        return release
    }

    private fun largeFrame(finishes: AtomicInteger, abandons: AtomicInteger, pointer: Long) =
        DecodePipeline.Stage.Frame(
            large = true,
            finish = { finishes.incrementAndGet(); buffer(pointer) },
            abandon = { abandons.incrementAndGet() },
        )

    @Test
    fun aWaitingRequestCancelledIsAnsweredAtOnceAndNeverDecoded() {
        val release = block(decodeQueue)
        val replies = Replies()
        val decodes = AtomicInteger()
        pipeline.start(replies.state(1), "a", 100L) {
            decodes.incrementAndGet()
            DecodePipeline.Stage.Reply(buffer(0x1))
        }

        pipeline.cancel(1)

        assertNull(replies.awaitOne().getOrNull())
        release.countDown()
        drain(decodeQueue)
        assertEquals(0, decodes.get())
        assertEquals(1, replies.results.size)
        assertEquals(0, registry.pendingCount)
    }

    @Test
    fun aDecodedRequestIsAnsweredOnceAndUnregistered() {
        val replies = Replies()
        pipeline.start(replies.state(2), "a", 100L) { DecodePipeline.Stage.Reply(buffer(0x2)) }

        assertEquals(0x2L, replies.awaitOne().getOrNull()?.get("pointer"))
        drain(decodeQueue)
        assertEquals(1, replies.results.size)
        assertEquals(0, registry.pendingCount)
        assertTrue(freed.isEmpty())
    }

    @Test
    fun aCancelThatArrivedFirstAnswersWithoutDecoding() {
        val replies = Replies()
        val decodes = AtomicInteger()
        pipeline.cancel(3)
        pipeline.start(replies.state(3), "a", 100L) {
            decodes.incrementAndGet()
            DecodePipeline.Stage.Reply(buffer(0x3))
        }

        assertNull(replies.awaitOne().getOrNull())
        drain(decodeQueue)
        assertEquals(0, decodes.get())
    }

    @Test
    fun aDecodeErrorIsAnsweredOnceAsAFailure() {
        val replies = Replies()
        pipeline.start(replies.state(4), "a", 100L) { throw NativeImageException("not_found", "gone") }

        val error = replies.awaitOne().exceptionOrNull() as NativeImageError
        assertEquals("not_found", error.code)
        assertEquals(0, registry.pendingCount)
    }

    @Test
    fun aSmallFrameIsFinishedInTheDecodeQueue() {
        val replies = Replies()
        val finishes = AtomicInteger()
        val release = block(frameQueue)
        pipeline.start(replies.state(5), "a", 100L) {
            DecodePipeline.Stage.Frame(
                large = false,
                finish = { finishes.incrementAndGet(); buffer(0x5) },
                abandon = { error("not abandoned") },
            )
        }

        // The frame stage is busy, so only the decode queue can have answered.
        assertEquals(0x5L, replies.awaitOne().getOrNull()?.get("pointer"))
        assertEquals(1, finishes.get())
        release.countDown()
    }

    @Test
    fun aLargeFrameRunsOnTheFrameStage() {
        val replies = Replies()
        val finishes = AtomicInteger()
        val abandons = AtomicInteger()
        val release = block(frameQueue)
        pipeline.start(replies.state(6), "v", 100L) { largeFrame(finishes, abandons, 0x6) }
        drain(decodeQueue)
        assertEquals(0, finishes.get())

        release.countDown()

        assertEquals(0x6L, replies.awaitOne().getOrNull()?.get("pointer"))
        assertEquals(1, finishes.get())
        assertEquals(0, abandons.get())
    }

    @Test
    fun aLargeFrameCancelledWhileWaitingForTheFrameStageIsAnsweredAtOnceAndAbandonedOnce() {
        val replies = Replies()
        val finishes = AtomicInteger()
        val abandons = AtomicInteger()
        val release = block(frameQueue)
        pipeline.start(replies.state(7), "v", 100L) { largeFrame(finishes, abandons, 0x7) }
        drain(decodeQueue)

        pipeline.cancel(7)

        assertNull(replies.awaitOne().getOrNull())
        assertEquals(1, abandons.get())
        release.countDown()
        drain(frameQueue)
        assertEquals(0, finishes.get())
        assertEquals(1, abandons.get())
        assertEquals(1, replies.results.size)
        assertEquals(0, registry.pendingCount)
    }

    @Test
    fun aRefusedFrameStageAbandonsAndAnswersOnce() {
        val refusing = DecodeQueue(1).also { it.shutdownNow() }
        val refusingPipeline = DecodePipeline(registry, decodeQueue, refusing, { freed.add(it) })
        val replies = Replies()
        val finishes = AtomicInteger()
        val abandons = AtomicInteger()
        refusingPipeline.start(replies.state(8), "v", 100L) { largeFrame(finishes, abandons, 0x8) }

        assertNull(replies.awaitOne().getOrNull())
        drain(decodeQueue)
        assertEquals(1, abandons.get())
        assertEquals(0, finishes.get())
        assertEquals(1, replies.results.size)
        assertEquals(0, registry.pendingCount)
    }

    @Test
    fun aBufferProducedAfterTheRequestSettledIsFreed() {
        val release = CountDownLatch(1)
        val decoding = CountDownLatch(1)
        val replies = Replies()
        pipeline.start(replies.state(9), "a", 100L) {
            decoding.countDown()
            release.await(5, TimeUnit.SECONDS)
            DecodePipeline.Stage.Reply(buffer(0x9))
        }
        assertTrue(decoding.await(5, TimeUnit.SECONDS))
        // Engine detach settles every pending request before workers finish.
        registry.cancelAll().forEach { it.finish(Result.success(null)) }
        release.countDown()

        assertNull(replies.awaitOne().getOrNull())
        drain(decodeQueue)
        assertEquals(listOf(0x9L), freed.toList())
    }

    @Test
    fun aLargeVideoCancelledWhileItsFrameIsBeingDecidedNeverWaitsForTheFrameStage() {
        val replies = Replies()
        val finishes = AtomicInteger()
        val abandons = AtomicInteger()
        val deciding = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val frameBusy = block(frameQueue)
        pipeline.start(replies.state(10), "v", 100L) {
            deciding.countDown()
            cancelled.await(5, TimeUnit.SECONDS)
            largeFrame(finishes, abandons, 0xA)
        }
        assertTrue(deciding.await(5, TimeUnit.SECONDS))

        pipeline.cancel(10)
        cancelled.countDown()

        // The frame stage is still busy, so an answer can only come from the decode queue.
        assertNull(replies.awaitOne().getOrNull())
        assertEquals(1, abandons.get())
        assertEquals(0, finishes.get())
        frameBusy.countDown()
    }

    @Test
    fun shutdownAnswersEveryRequestAndReleasesFramesThatNeverStarted() {
        val replies = Replies()
        val finishes = AtomicInteger()
        val abandons = AtomicInteger()
        val release = block(frameQueue)
        pipeline.start(replies.state(11), "v", 100L) { largeFrame(finishes, abandons, 0xB) }
        drain(decodeQueue)

        pipeline.shutdown()
        release.countDown()

        assertNull(replies.awaitOne().getOrNull())
        assertEquals(1, abandons.get())
        assertEquals(0, finishes.get())
        assertEquals(0, registry.pendingCount)
    }
}
