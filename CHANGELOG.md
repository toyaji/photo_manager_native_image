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
  size of one video shows the same frame.
- `NativeAssetHdrView`: an opt-in native view that applies the gain map of
  Apple HDR HEIC and Ultra HDR JPEG photos on HDR displays (iOS 17+,
  Android 14+). It stays transparent for SDR photos and on failure, keeps the
  last three decoded HDR images, and fades in on first decode.
- Flutter 3.24 or later; Swift Package Manager builds need Flutter 3.44.
