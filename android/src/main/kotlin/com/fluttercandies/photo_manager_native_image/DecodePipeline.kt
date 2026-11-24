package com.fluttercandies.photo_manager_native_image

/**
 * Carries a request through the decode queue and, for frames of large videos, the
 * frame stage (a one-thread [DecodeQueue]), and sends its one reply. It holds no
 * Android types so every path can be tested on the JVM.
 *
 * [start] and [cancel] are called on the main thread.
 */
class DecodePipeline(
    private val registry: RequestRegistry,
    private val decodeQueue: DecodeQueue,
    private val frameStage: DecodeQueue,
    private val free: (Long) -> Unit,
) {
    /** What the decode-queue work produced. */
    sealed interface Stage {
        class Reply(val result: Result<NativeImageReply?>) : Stage

        /**
         * A frame still has to be decoded. [large] frames go to the frame stage;
         * [abandon] releases what [finish] would have used.
         */
        class Frame(
            val large: Boolean,
            val finish: () -> Result<NativeImageReply?>,
            val abandon: () -> Unit,
        ) : Stage
    }

    /** Runs [decode] for [state] in the decode queue, ordered by [pixels] and [assetId]. */
    fun start(state: RequestState, assetId: String, pixels: Long, decode: () -> Stage) {
        if (!registry.register(state)) {
            state.finish(Result.success(null))
            return
        }
        submit({ state.queuedTask = decodeQueue.execute(pixels, assetId, it) }, state) {
            if (state.isCancelled) {
                return@submit Result.success(null)
            }
            when (val stage = decode()) {
                is Stage.Reply -> stage.result
                is Stage.Frame -> if (!stage.large || state.isCancelled) {
                    // A request cancelled while deciding never waits for the frame stage.
                    runFrame(state, stage)
                } else {
                    val queued = submit({
                        state.frameAbandon = stage.abandon
                        state.frameTask = frameStage.execute(pixels, assetId, it)
                    }, state) { runFrame(state, stage) }
                    if (!queued) {
                        stage.abandon()
                    } else if (state.isCancelled) {
                        // A cancel that came between the check above and the hand-off found no task to take back.
                        takeBackFrame(state)
                    }
                    // The frame stage replies.
                    null
                }
            }
        }
    }

    /**
     * A request still waiting in the decode queue or the frame stage is taken out
     * and answered at once; otherwise the running stage sees the cancel and answers.
     */
    fun cancel(requestId: Long) {
        val state = registry.cancel(requestId) ?: return
        val queued = state.queuedTask
        if (queued != null && decodeQueue.remove(queued)) {
            registry.remove(requestId)
            state.finish(Result.success(null))
            return
        }
        takeBackFrame(state)
    }

    /** Answers [state] at once if its frame task is still waiting; otherwise the frame stage answers. */
    private fun takeBackFrame(state: RequestState) {
        val frame = state.frameTask
        if (frame != null && frameStage.remove(frame)) {
            state.frameAbandon?.invoke()
            registry.remove(state.requestId)
            state.finish(Result.success(null))
        }
    }

    /**
     * Engine detach: answers every pending request, stops both queues, and releases
     * what the frame tasks that never started were holding.
     */
    fun shutdown() {
        val states = registry.cancelAll()
        states.forEach { it.finish(Result.success(null)) }
        decodeQueue.shutdownNow()
        val notStarted = frameStage.shutdownNow().toSet()
        for (state in states) {
            if (state.frameTask in notStarted) state.frameAbandon?.invoke()
        }
    }

    private fun runFrame(state: RequestState, stage: Stage.Frame): Result<NativeImageReply?> =
        if (state.isCancelled) {
            stage.abandon()
            Result.success(null)
        } else {
            stage.finish()
        }

    /**
     * Hands [work] to [execute] and sends the request's one reply from what it returns; a
     * `null` means a later stage replies. Returns `false` if [execute] refused the work, in
     * which case the request has already been answered with `null`.
     */
    private fun submit(
        execute: (Runnable) -> Unit,
        state: RequestState,
        work: () -> Result<NativeImageReply?>?,
    ): Boolean {
        try {
            execute(Runnable {
                val result = try {
                    work() ?: return@Runnable
                } catch (e: NativeImageException) {
                    Result.failure(NativeImageError(e.code, e.message))
                } catch (t: Throwable) {
                    // An uncaught error on a pool thread would kill the
                    // process; a request must still get its one reply.
                    Result.failure(NativeImageError("decode_failed", t.toString()))
                }
                registry.remove(state.requestId)
                state.deliver(result, free)
            })
            return true
        } catch (_: Exception) {
            registry.remove(state.requestId)
            state.finish(Result.success(null))
            return false
        }
    }
}
