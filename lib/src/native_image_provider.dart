// Copyright 2026 The FlutterCandies author. All rights reserved.
// Use of this source code is governed by an Apache license that can be found
// in the LICENSE file.

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';
import 'package:photo_manager/photo_manager.dart';

import 'android_decode_order.dart';
import 'decode_hints.dart';
import 'native_image_channel.dart';
import 'native_image_request.dart';
import 'native_image_stream_completer.dart';

/// Loads an [AssetEntity] thumbnail decoded natively to RGBA.
///
/// [size] is the pixel length of the shorter side (aspect fill); which sizes an
/// app uses, and how many, is the app's policy. The cache key is
/// `(entity.id, modified date, size, isVideo)`, so an edited asset reloads.
///
/// iOS: an asset that is only in iCloud is tried locally first; if PhotoKit
/// needs the network for this size, one download follows on a low-priority
/// queue.
class NativeImageProvider extends ImageProvider<NativeImageProvider> {
  /// Creates a provider for [entity] at [size] pixels.
  NativeImageProvider(
    this.entity, {
    required this.size,
    this.androidOriginalDecodeAbove = defaultAndroidOriginalDecodeAbove,
  }) : assert(size > 0),
       assert(androidOriginalDecodeAbove >= 0);

  /// Default for [androidOriginalDecodeAbove].
  static const int defaultAndroidOriginalDecodeAbove = 256;

  /// The asset to load.
  final AssetEntity entity;

  /// Pixel length of the shorter side of the decoded image.
  final int size;

  /// Android 10+, images only: a [size] above this decodes from the original;
  /// at or below it the MediaStore system thumbnail is used when it is large
  /// enough. The system thumbnail is faster but a recompressed JPEG, so it
  /// looks soft at larger sizes. `0` always decodes the original.
  final int androidOriginalDecodeAbove;

  int get _modifiedDateSecond =>
      entity.modifiedDateSecond ?? entity.createDateSecond ?? 0;

  bool get _isVideo => entity.type == AssetType.video;

  /// The order in which Android starts waiting loads, for the whole app; the
  /// last value set wins, so a screen that changes it should put the previous
  /// value back when it goes away. [AndroidDecodeOrder.requestOrder] by
  /// default. It can be set at any time, also before `runApp`: until the first
  /// load it is only kept in Dart; from then on a change is sent at once and
  /// re-orders the loads already waiting. Other platforms ignore it.
  static AndroidDecodeOrder get androidDecodeOrder =>
      DecodeHints.instance.order;

  static set androidDecodeOrder(AndroidDecodeOrder value) =>
      DecodeHints.instance.order = value;

  @override
  Future<NativeImageProvider> obtainKey(ImageConfiguration configuration) {
    return SynchronousFuture<NativeImageProvider>(this);
  }

  @override
  ImageStreamCompleter loadImage(
    NativeImageProvider key,
    ImageDecoderCallback decode,
  ) {
    final NativeImageRequest request = NativeImageRequest(
      channel: NativeImageChannel.instance,
      assetId: entity.id,
      size: size,
      isVideo: _isVideo,
      androidOriginalDecodeAbove: androidOriginalDecodeAbove,
    );
    return NativeImageStreamCompleter(
      load: request.load,
      onCancel: () {
        request.cancel();
        PaintingBinding.instance.imageCache.evict(key);
      },
      // Like NetworkImage: a failed key must not be served from the pending
      // cache, so the next resolve of the same asset starts a new load.
      onFailure: () => PaintingBinding.instance.imageCache.evict(key),
      debugLabel: '${entity.id}@$size',
    );
  }

  @override
  bool operator ==(Object other) {
    if (other.runtimeType != runtimeType) {
      return false;
    }
    return other is NativeImageProvider &&
        other.entity.id == entity.id &&
        other._modifiedDateSecond == _modifiedDateSecond &&
        other.size == size &&
        other._isVideo == _isVideo &&
        other.androidOriginalDecodeAbove == androidOriginalDecodeAbove;
  }

  @override
  int get hashCode => Object.hash(
    entity.id,
    _modifiedDateSecond,
    size,
    _isVideo,
    androidOriginalDecodeAbove,
  );

  @override
  String toString() =>
      '${objectRuntimeType(this, 'NativeImageProvider')}(${entity.id}, size: $size)';
}
