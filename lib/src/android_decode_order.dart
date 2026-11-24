// Copyright 2026 The FlutterCandies author. All rights reserved.
// Use of this source code is governed by an Apache license that can be found
// in the LICENSE file.

/// Order in which Android starts loads that are waiting for a decode thread,
/// set for the whole app with `NativeImageProvider.androidDecodeOrder`. Assets
/// named by a live `AndroidDecodePriority` go first: before every other waiting
/// load in [requestOrder], before other loads of the same pixel size in
/// [smallestThenNewest]. Other platforms keep their own order.
enum AndroidDecodeOrder {
  /// In the order the loads were requested. The default.
  requestOrder,

  /// The smallest load first and, among loads of the same pixel size, the
  /// newest first. Suits a grid that asks each cell for a small placeholder and
  /// a sharp image: placeholders of cells that just appeared never wait behind
  /// sharp images, and cells scrolled past (the oldest loads) go last. A large
  /// load waits for as long as smaller loads keep arriving.
  smallestThenNewest,
}
