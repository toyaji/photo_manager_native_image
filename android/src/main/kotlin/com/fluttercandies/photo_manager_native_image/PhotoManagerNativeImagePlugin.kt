package com.fluttercandies.photo_manager_native_image

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PhotoManagerNativeImagePlugin : FlutterPlugin, NativeImageHostApi {
    private lateinit var decoder: NativeImageDecoder
    private val registry = RequestRegistry()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** One engine attachment; a plugin instance can be attached again later. */
    private class Attachment(registry: RequestRegistry) {
        val decodeQueue = DecodeQueue(Runtime.getRuntime().availableProcessors() / 2 + 1)

        // A retriever frame holds a decoder at the video's own size (an 8K frame is hundreds
        // of MB), so frames of videos above 4K are decoded one at a time, apart from the pool.
        val frameQueue = DecodeQueue(1)

        val pipeline = DecodePipeline(registry, decodeQueue, frameQueue, NativeBuffer::free)

        // Read and written on the main thread only.
        var attached = true
    }

    private var attachment: Attachment? = null
    private var hdrExecutor: ExecutorService? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        decoder = NativeImageDecoder(binding.applicationContext)
        attachment = Attachment(registry)
        NativeImageHostApi.setUp(binding.binaryMessenger, this)
        val hdr = Executors.newSingleThreadExecutor()
        hdrExecutor = hdr
        binding.platformViewRegistry.registerViewFactory(
            "photo_manager_native_image/hdr_view",
            HdrAssetViewFactory(decoder, hdr),
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        NativeImageHostApi.setUp(binding.binaryMessenger, null)
        val current = attachment
        if (current == null) {
            registry.cancelAll().forEach { it.finish(Result.success(null)) }
        } else {
            current.attached = false
            current.pipeline.shutdown()
        }
        attachment = null
        hdrExecutor?.shutdownNow()
        hdrExecutor = null
    }

    override fun requestImage(
        assetId: String,
        requestId: Long,
        width: Long,
        height: Long,
        isVideo: Boolean,
        allowNetwork: Boolean,
        originalDecodeAbove: Long,
        callback: (Result<NativeImageReply?>) -> Unit,
    ) {
        val current = attachment ?: return callback(Result.success(null))
        // Replies must be posted from the main thread. The engine drops a reply
        // sent after it detached, so a buffer in such a reply is freed here.
        val state = RequestState(requestId) { result ->
            mainHandler.post {
                replyOrFree(result, current.attached, callback, NativeBuffer::free)
            }
        }
        current.pipeline.start(state, assetId, width * height) {
            when (val step = decoder.decode(assetId, width.toInt(), height.toInt(), isVideo, originalDecodeAbove.toInt())) {
                is DecodeStep.Done -> DecodePipeline.Stage.Reply(reply(state, step.bitmap))
                is DecodeStep.NeedsFrame -> DecodePipeline.Stage.Frame(
                    large = step.large,
                    finish = { reply(state, step.finish()) },
                    abandon = step.abandon,
                )
            }
        }
    }

    override fun cancelRequest(requestId: Long) {
        val pipeline = attachment?.pipeline
        if (pipeline != null) pipeline.cancel(requestId) else registry.cancel(requestId)
    }

    override fun setPriorityAssets(assetIds: List<String>) {
        val current = attachment ?: return
        val set = assetIds.toSet()
        current.decodeQueue.prefer(set)
        current.frameQueue.prefer(set)
    }

    override fun setDecodeOrder(order: Long) {
        val next = when (order) {
            0L -> DecodeQueue.Order.REQUEST
            1L -> DecodeQueue.Order.SMALLEST_FIRST
            else -> return
        }
        val current = attachment ?: return
        current.decodeQueue.setOrder(next)
        current.frameQueue.setOrder(next)
    }

    private fun reply(state: RequestState, bitmap: Bitmap): Result<NativeImageReply?> {
        if (state.isCancelled) {
            bitmap.recycle()
            return Result.success(null)
        }
        // No cancel checks past this point: the buffer is Dart's to free.
        return try {
            Result.success(copyToNativeBuffer(bitmap))
        } catch (e: Exception) {
            Result.failure(NativeImageError("decode_failed", e.toString()))
        } finally {
            bitmap.recycle()
        }
    }

    private fun copyToNativeBuffer(bitmap: Bitmap): NativeImageReply {
        val rowBytes = bitmap.rowBytes
        val size = rowBytes.toLong() * bitmap.height
        val pointer = NativeBuffer.allocate(size)
        if (pointer == 0L) throw IllegalStateException("malloc($size) failed")
        try {
            bitmap.copyPixelsToBuffer(NativeBuffer.wrap(pointer, size))
        } catch (e: Exception) {
            NativeBuffer.free(pointer)
            throw e
        }
        return mapOf(
            "pointer" to pointer,
            "width" to bitmap.width.toLong(),
            "height" to bitmap.height.toLong(),
            "rowBytes" to rowBytes.toLong(),
        )
    }
}
