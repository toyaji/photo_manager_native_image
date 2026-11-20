## 0.1.1

- Android 10+: a video thumbnail smaller than the requested size is no longer
  returned as is. MediaStore keeps one thumbnail per video, fitted in half the
  screen's shorter side, so a 400 px request on a 1080 × 2400 screen came back
  304 px tall. The picture is now decoded from the video at the requested size,
  chosen the way the system thumbnail is (cover art, else the sync frame
  nearest half the duration), so every size still shows the same frame. A
  video whose system thumbnail is some other frame keeps the thumbnail.
- Example app: Android Gradle Plugin 9.3.1, Kotlin 2.4.20 and Gradle 9.5.0,
  the versions current Flutter requires.

## 0.1.0

First release.

- `NativeImageProvider` and `NativeAssetImage`: load a `photo_manager`
  `AssetEntity` thumbnail as native RGBA pixels handed to Flutter through
  `dart:ffi` and `ImageDescriptor.raw`, with no JPEG round trip.
- Cancellation that recognises the `ImageCache` listener, so scrolled-away
  cells stop their native work without false cancels on `imageCache.clear()`.
- Decoded pixels capped at `size² × 4`, so panoramas stay bounded.
- `NativeImageException` / `NativeImageErrorCode`: `notFound`,
  `icloudNotDownloaded`, `decodeFailed`.
- iOS: `PHImageManager.requestImage` on a worker queue, `vImage` downscale,
  XPC reconnect retry. iCloud-only assets are tried locally first, then once
  with network access on a low-priority queue; cancelling stops the download.
- Android: `ImageDecoder` on API 29+, decoded to the requested size in sRGB;
  `MediaStore.*.Thumbnails` and `BitmapFactory` with orientation correction on
  API 26–28; JNI `malloc` buffers. Images above `androidOriginalDecodeAbove`
  (256 px by default) decode from the original, below it from the MediaStore
  system thumbnail. Videos keep the system thumbnail up to 768 px, so every
  size of one video shows the same frame (replaced in 0.1.1).
- `NativeAssetHdrView`: an opt-in native view that applies the gain map of
  Apple HDR HEIC and Ultra HDR JPEG photos on HDR displays (iOS 17+,
  Android 14+). It stays transparent for SDR photos and on failure, keeps the
  last three decoded HDR images, and fades in on first decode.
- Flutter 3.24 or later; Swift Package Manager builds need Flutter 3.44.
