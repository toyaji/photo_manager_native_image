package com.fluttercandies.photo_manager_native_image

import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The decode pool. Waiting work starts in [Order]; within it, assets named with
 * [prefer] go first. A task that has not started can be taken back with [remove].
 * Safe to call from any thread.
 */
class DecodeQueue(threads: Int) {
    enum class Order {
        /** Priority assets first, then in the order requests arrived. */
        REQUEST,

        /** Smallest request first; then priority assets; then the newest. */
        SMALLEST_FIRST,
    }

    private val sequence = AtomicLong()

    // Guards [order] and [priorityAssets] against [execute] from another thread; the
    // comparator reads [order] under the queue's own lock, and it changes only while drained.
    private val lock = Any()

    @Volatile
    private var order = Order.REQUEST
    private var priorityAssets: Set<String> = emptySet()

    private val queue = PriorityBlockingQueue<Runnable>(16) { a, b -> compare(a as DecodeTask, b as DecodeTask) }
    private val executor = ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, queue).apply {
        // Work put back by [reorder] goes straight into the queue, so every thread must already exist.
        prestartAllCoreThreads()
    }

    /**
     * Queues [body] for a request of [pixels] output pixels of [assetId] and
     * returns the queued task for [remove]. Throws `RejectedExecutionException`
     * after [shutdownNow].
     */
    fun execute(pixels: Long, assetId: String, body: Runnable): Runnable {
        val task = DecodeTask(pixels, sequence.incrementAndGet(), assetId, body)
        synchronized(lock) {
            task.preferred = assetId in priorityAssets
            executor.execute(task)
        }
        return task
    }

    /** Takes [task] out of the queue. `false` if it already started or ran. */
    fun remove(task: Runnable): Boolean = synchronized(lock) { executor.remove(task) }

    /** Replaces the priority assets. Waiting tasks keep their identity, so a task held for [remove] stays valid. */
    fun prefer(assetIds: Set<String>) = synchronized(lock) {
        if (assetIds != priorityAssets) reorder { priorityAssets = assetIds }
    }

    fun setOrder(next: Order) = synchronized(lock) {
        if (next != order) reorder { order = next }
    }

    /** Stops the threads and returns the tasks that never started. */
    fun shutdownNow(): List<Runnable> = executor.shutdownNow()

    /** Drains the waiting tasks, applies [change], and puts them back in the new order. Holds [lock]. */
    private fun reorder(change: () -> Unit) {
        val waiting = ArrayList<Runnable>(queue.size)
        queue.drainTo(waiting)
        change()
        for (task in waiting) {
            (task as DecodeTask).preferred = task.assetId in priorityAssets
        }
        queue.addAll(waiting)
    }

    private fun compare(a: DecodeTask, b: DecodeTask): Int = when (order) {
        Order.REQUEST -> compareValuesBy(a, b, { !it.preferred }, { it.sequence })
        Order.SMALLEST_FIRST -> compareValuesBy(a, b, { it.pixels }, { !it.preferred }, { -it.sequence })
    }

    /**
     * Identity equality on purpose: [remove] finds the task by `equals`.
     * [preferred] changes only while the task is out of the queue.
     */
    private class DecodeTask(
        val pixels: Long,
        val sequence: Long,
        val assetId: String,
        private val body: Runnable,
    ) : Runnable {
        var preferred = false

        override fun run() = body.run()
    }
}
