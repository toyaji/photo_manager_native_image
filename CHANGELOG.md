## 0.2.0

- **Breaking:** Android 10 (API 29) or later. The decode path for Android
  8–9, which used `BitmapFactory` and `MINI_KIND` thumbnails, is removed. Pub
  cannot see an app's `minSdk`, so an app below 29 must either set
  `minSdk = 29` or pin `photo_manager_native_image: ^0.1.2`.
- **Breaking:** iOS is built only through Swift Package Manager; the CocoaPods
  podspec is removed, and the package now needs Flutter 3.44, the first stable
  release with Swift Package Manager on by default. Apps on older Flutter keep
  resolving to 0.1.x. An app that turns Swift Package Manager off
  (`flutter: config: enable-swift-package-manager: false`) gets a build error
  naming this plugin. Swift Package Manager is how Flutter
  builds iOS plugins by default from 3.44, and keeping CocoaPods meant two iOS
  integrations to build and test for the same sources.
- Android 10: a video's thumbnail is decoded from the same frame as its system
  thumbnail. Android 10 picks the sync frame near the start, not the middle,
  so before this a request larger than the system thumbnail could show
  another picture, or a soft one.
- Nothing else changes from 0.1.2. 0.1.2 is the last 0.1.x release: later
  fixes ship only in 0.2.x, so an app that needs them moves to Flutter 3.44
  and Android 10.

## 0.1.2

- Android: frames of videos with more than 3840 × 2160 pixels (DCI 4K and up),
  by the size MediaStore records, are decoded one at a time; a video whose
  size MediaStore does not know is decoded in parallel as before. Each video
  frame load now reads that size once. A `MediaMetadataRetriever` frame holds a decoder
  at the video's own size, so several such frames at once can take hundreds of
  MB. Smaller videos, photos and system thumbnails are still decoded in
  parallel. Folders of such videos fill more slowly.
- Android: a request cancelled while it waits for a decode thread, or for the
  frame thread above, is taken out and answered at once instead of when its
  turn comes. While such a frame waits, only a small sample of the system
  thumbnail is kept for the same-frame check, not the thumbnail itself. Decode threads are started when the engine attaches.
- New `NativeImageProvider.androidDecodeOrder`. The default,
  `AndroidDecodeOrder.requestOrder`, keeps the order of 0.1.1.
  `AndroidDecodeOrder.smallestThenNewest` starts the smallest waiting load
  first and, within a size, the newest, for grids that stack a small
  placeholder under a sharp image.
- New `AndroidDecodePriority`: a screen names the assets it shows so their
  waiting loads start first within the decode order. Every live instance
  counts. Android only.
- Both settings are kept in Dart until the first load, then sent as they
  change, so they can be set before `runApp` and survive a hot restart.

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
