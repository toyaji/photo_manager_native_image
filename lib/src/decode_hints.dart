// Copyright 2026 The FlutterCandies author. All rights reserved.
// Use of this source code is governed by an Apache license that can be found
// in the LICENSE file.

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'android_decode_order.dart';
import 'native_image_channel.dart';

/// The decode order and priority assets the app chose, kept in Dart and
/// mirrored to the platform.
///
/// Nothing is sent until the first load of this isolate; that load first sends
/// the whole state. So a value set before the binding exists is not lost, and
/// after a hot restart (Dart state reset, platform state kept) the platform is
/// brought back to what this isolate says. A send the platform cannot take
/// (no plugin in a widget test) is retried with the next load.
class DecodeHints {
  DecodeHints._();

  /// The one instance for this isolate.
  static final DecodeHints instance = DecodeHints._();

  AndroidDecodeOrder _order = AndroidDecodeOrder.requestOrder;
  Set<String> _priorityAssets = const <String>{};
  bool _synced = false;

  /// The order the app chose; see `NativeImageProvider.androidDecodeOrder`.
  AndroidDecodeOrder get order => _order;

  set order(AndroidDecodeOrder value) {
    if (value == _order) {
      return;
    }
    _order = value;
    if (_synced) {
      _send((NativeImageChannel c) => c.setDecodeOrder(_wire(value)));
    }
  }

  /// The union of every live `AndroidDecodePriority`.
  set priorityAssets(Set<String> value) {
    if (setEquals(value, _priorityAssets)) {
      return;
    }
    _priorityAssets = value;
    if (_synced) {
      _send((NativeImageChannel c) => c.setPriorityAssets(value.toList()));
    }
  }

  /// Called before every load is sent; sends the whole state once.
  void ensureSynced() {
    if (_synced) {
      return;
    }
    _synced = true;
    _send((NativeImageChannel c) => c.setDecodeOrder(_wire(_order)));
    _send((NativeImageChannel c) => c.setPriorityAssets(_priorityAssets.toList()));
  }

  void _send(Future<void> Function(NativeImageChannel channel) call) {
    call(NativeImageChannel.instance).then<void>(
      (_) {},
      onError: (Object error, StackTrace stackTrace) {
        if (error is PlatformException || error is MissingPluginException) {
          _synced = false;
          return;
        }
        Error.throwWithStackTrace(error, stackTrace);
      },
    );
  }

  static int _wire(AndroidDecodeOrder order) => switch (order) {
        AndroidDecodeOrder.requestOrder => 0,
        AndroidDecodeOrder.smallestThenNewest => 1,
      };

  /// Forgets everything, as a hot restart does. For tests.
  void debugReset() {
    _order = AndroidDecodeOrder.requestOrder;
    _priorityAssets = const <String>{};
    _synced = false;
  }
}
