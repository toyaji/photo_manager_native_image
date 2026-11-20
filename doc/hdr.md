# HDR photos

`NativeAssetImage` hands Flutter 8-bit sRGB pixels, so a gain map (Apple HDR
HEIC, Ultra HDR JPEG) is not applied. Stack `NativeAssetHdrView` above the
regular image to show the HDR version on HDR-capable displays:

```dart
Stack(
  fit: StackFit.expand,
  children: <Widget>[
    NativeAssetImage(entity, size: 1080, fit: BoxFit.cover),
    NativeAssetHdrView(entity, size: 1080),
  ],
)
```

- The view is transparent for SDR photos, on iOS before 17, on Android before
  14, and on any failure, so the image below always shows through.
- It fills its box like `BoxFit.cover`, ignores pointers, and decodes the
  shorter side to about `size` pixels (on Android, between `size` and twice
  `size`, since it samples by powers of two). A new `size` or a modified asset
  creates a new view.
- Android only: HDR output needs the activity window in HDR color mode. The
  plugin does not touch it; set it from the app, for example with
  `window.colorMode = ActivityInfo.COLOR_MODE_HDR` while the view is shown.

## How it draws

| | iOS 17+ | Android 14+ |
|---|---|---|
| View | `UIImageView` with `preferredImageDynamicRange: .high` | `ImageView` in a Hybrid Composition platform view |
| Decode | The on-device original with its gain map | A 1/16 decode checks for a gain map first, so SDR photos are never decoded at full size; API 35+ scales the bitmap down to `size` unless that would lose the gain map |
| HDR output | Automatic on HDR displays | Only while the activity window is in `ActivityInfo.COLOR_MODE_HDR`, which the app sets |

The last three decoded HDR images are kept (and assets without a gain map are
remembered), so a page that comes back draws at once instead of decoding the
original again; the cache is cleared 10 s after the last view is gone. A first
decode fades in over 0.2 s instead of snapping over the base image.

[← Back to README](https://github.com/toyaji/photo_manager_native_image/blob/main/README.md)
