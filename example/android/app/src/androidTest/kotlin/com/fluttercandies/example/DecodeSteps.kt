package com.fluttercandies.example

import android.graphics.Bitmap
import com.fluttercandies.photo_manager_native_image.DecodeStep
import com.fluttercandies.photo_manager_native_image.NativeImageDecoder

/** Runs a decode to its bitmap, through the frame stage when the decoder asks for one. */
fun NativeImageDecoder.decodeBitmap(
    assetId: String,
    width: Int,
    height: Int,
    isVideo: Boolean,
    originalDecodeAbove: Int,
): Bitmap = when (val step = decode(assetId, width, height, isVideo, originalDecodeAbove)) {
    is DecodeStep.Done -> step.bitmap
    is DecodeStep.NeedsFrame -> step.finish()
}
