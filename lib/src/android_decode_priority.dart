// Copyright 2026 The FlutterCandies author. All rights reserved.
// Use of this source code is governed by an Apache license that can be found
// in the LICENSE file.

import 'package:flutter/foundation.dart';

import 'decode_hints.dart';

/// Android only: the assets one screen shows right now. With
/// `AndroidDecodeOrder.requestOrder` their waiting loads start before every
/// other waiting load; with `AndroidDecodeOrder.smallestThenNewest` only before
/// other loads of the same pixel size, so a visible large load still waits
/// behind smaller ones. Other platforms ignore it.
///
/// Create one per screen, call [update] on the first layout and when the
/// visible assets change, and [dispose] it with the screen. Pass only what is
/// visible: every change re-orders the waiting loads. The assets of every live
/// instance count together, so two screens do not undo each other; an instance
/// that is never disposed keeps its assets ahead for the rest of the app's life.
class AndroidDecodePriority {
  /// Creates an empty set that takes part until [dispose].
  AndroidDecodePriority() {
    _live.add(this);
  }

  static final Set<AndroidDecodePriority> _live = <AndroidDecodePriority>{};

  Set<String> _assetIds = const <String>{};
  bool _disposed = false;

  /// The asset ids this screen currently favours.
  Set<String> get assetIds => Set<String>.unmodifiable(_assetIds);

  /// Replaces this screen's assets. Calling it with the same assets sends
  /// nothing, so it can be called from a scroll listener.
  void update(Iterable<String> assetIds) {
    assert(!_disposed, 'update() after dispose()');
    if (_disposed) {
      return;
    }
    final Set<String> next = assetIds.toSet();
    if (setEquals(next, _assetIds)) {
      return;
    }
    _assetIds = next;
    _publish();
  }

  /// Stops favouring this screen's assets. Calling it again does nothing.
  void dispose() {
    if (_disposed) {
      return;
    }
    _disposed = true;
    _live.remove(this);
    _assetIds = const <String>{};
    _publish();
  }

  static void _publish() {
    DecodeHints.instance.priorityAssets = <String>{
      for (final AndroidDecodePriority priority in _live) ...priority._assetIds,
    };
  }

  /// Forgets every live instance and resets `NativeImageProvider.androidDecodeOrder`,
  /// as a hot restart does.
  @visibleForTesting
  static void debugReset() {
    _live.clear();
    DecodeHints.instance.debugReset();
  }
}
