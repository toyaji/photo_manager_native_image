package com.fluttercandies.photo_manager_native_image

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeQueueTest {
    /** Occupies the queue's only thread so later tasks wait in the queue. */
    private fun blocked(queue: DecodeQueue): CountDownLatch {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        queue.execute(1L, "busy", Runnable {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        assertTrue(started.await(5, TimeUnit.SECONDS))
        return release
    }

    @Test
    fun smallestFirstRunsSmallerRequestsFirstAndNewestFirstWithinASize() {
        val queue = DecodeQueue(1)
        queue.setOrder(DecodeQueue.Order.SMALLEST_FIRST)
        val release = blocked(queue)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(4)
        fun enqueue(name: String, pixels: Long) =
            queue.execute(pixels, name, Runnable { order.add(name); done.countDown() })

        enqueue("big-old", 400L * 400)
        enqueue("small-old", 128L * 128)
        enqueue("big-new", 400L * 400)
        enqueue("small-new", 128L * 128)
        release.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("small-new", "small-old", "big-new", "big-old"), order)
        queue.shutdownNow()
    }

    @Test
    fun removedTaskNeverRuns() {
        val queue = DecodeQueue(1)
        val release = blocked(queue)
        val ran = Collections.synchronizedList(mutableListOf<String>())
        val after = CountDownLatch(1)
        val removed = queue.execute(128L * 128, "a", Runnable { ran.add("removed") })
        queue.execute(400L * 400, "b", Runnable { ran.add("kept"); after.countDown() })

        assertTrue(queue.remove(removed))
        release.countDown()

        assertTrue(after.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("kept"), ran)
        queue.shutdownNow()
    }

    @Test
    fun startedTaskCannotBeRemoved() {
        val queue = DecodeQueue(1)
        val blocker = blocked(queue)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        // Queued behind the blocker, so it starts from the queue rather than as a thread's first task.
        val running = queue.execute(1L, "a", Runnable {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        blocker.countDown()
        assertTrue(started.await(5, TimeUnit.SECONDS))

        assertFalse(queue.remove(running))
        release.countDown()
        queue.shutdownNow()
    }

    @Test(expected = RejectedExecutionException::class)
    fun executeAfterShutdownIsRejected() {
        val queue = DecodeQueue(1)
        queue.shutdownNow()
        queue.execute(1L, "busy", Runnable {})
    }

    @Test
    fun smallestFirstPutsPriorityAssetsFirstWithinASizeOnly() {
        val queue = DecodeQueue(1)
        queue.setOrder(DecodeQueue.Order.SMALLEST_FIRST)
        queue.prefer(setOf("on-a", "on-b"))
        val release = blocked(queue)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(4)
        fun enqueue(name: String, pixels: Long) =
            queue.execute(pixels, name, Runnable { order.add(name); done.countDown() })

        enqueue("on-a", 400L * 400)
        enqueue("off", 400L * 400)
        enqueue("off-small", 128L * 128)
        enqueue("on-b", 400L * 400)
        release.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("off-small", "on-b", "on-a", "off"), order)
        queue.shutdownNow()
    }

    @Test
    fun preferReordersWaitingTasksAndKeepsThemRemovable() {
        val queue = DecodeQueue(1)
        val release = blocked(queue)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(2)
        fun enqueue(name: String) =
            queue.execute(400L * 400, name, Runnable { order.add(name); done.countDown() })

        enqueue("old")
        val gone = enqueue("gone")
        enqueue("new")
        queue.prefer(setOf("old", "gone"))
        assertTrue(queue.remove(gone))
        release.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("old", "new"), order)
        queue.shutdownNow()
    }

    @Test
    fun preferLeavesARunningTaskAlone() {
        val queue = DecodeQueue(1)
        val blocker = blocked(queue)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val runs = AtomicInteger()
        val running = queue.execute(1L, "a", Runnable {
            runs.incrementAndGet()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            finished.countDown()
        })
        blocker.countDown()
        assertTrue(started.await(5, TimeUnit.SECONDS))

        queue.prefer(setOf("a"))
        assertFalse(queue.remove(running))
        release.countDown()

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, runs.get())
        queue.shutdownNow()
    }

    @Test
    fun requestOrderIsTheDefaultAndPutsPriorityAssetsFirst() {
        val queue = DecodeQueue(1)
        queue.prefer(setOf("on"))
        val release = blocked(queue)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(4)
        fun enqueue(name: String, pixels: Long) =
            queue.execute(pixels, name, Runnable { order.add(name); done.countDown() })

        enqueue("big-first", 400L * 400)
        enqueue("small-second", 128L * 128)
        enqueue("on", 400L * 400)
        enqueue("small-last", 128L * 128)
        release.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("on", "big-first", "small-second", "small-last"), order)
        queue.shutdownNow()
    }

    @Test
    fun changingTheOrderReordersWaitingTasksAndKeepsThemRemovable() {
        val queue = DecodeQueue(1)
        val release = blocked(queue)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(2)
        fun enqueue(name: String, pixels: Long) =
            queue.execute(pixels, name, Runnable { order.add(name); done.countDown() })

        enqueue("big", 400L * 400)
        val gone = enqueue("gone", 64L * 64)
        enqueue("small", 128L * 128)
        queue.setOrder(DecodeQueue.Order.SMALLEST_FIRST)
        assertTrue(queue.remove(gone))
        release.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("small", "big"), order)
        queue.shutdownNow()
    }
}
