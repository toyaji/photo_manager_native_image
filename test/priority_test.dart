import 'package:flutter_test/flutter_test.dart';
import 'package:photo_manager_native_image/photo_manager_native_image.dart';
import 'package:photo_manager_native_image/src/decode_hints.dart';
import 'package:photo_manager_native_image/src/native_image_channel.dart';
import 'package:photo_manager_native_image/src/native_image_request.dart';

import 'fake_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late FakeChannel channel;
  final DecodeHints hints = DecodeHints.instance;

  setUp(() {
    channel = FakeChannel();
    NativeImageChannel.instance = channel;
    AndroidDecodePriority.debugReset();
  });

  tearDown(() {
    NativeImageChannel.instance = const PigeonNativeImageChannel();
    AndroidDecodePriority.debugReset();
  });

  test('nothing is sent before the first load, then the whole state once', () async {
    NativeImageProvider.androidDecodeOrder = AndroidDecodeOrder.smallestThenNewest;
    AndroidDecodePriority().update(<String>['a']);
    expect(channel.calls, isEmpty);

    final NativeImageRequest request = NativeImageRequest(
      channel: channel,
      assetId: 'x',
      size: 32,
      isVideo: false,
      androidOriginalDecodeAbove: 256,
    );
    final Future<Object?> loading = request.load();
    await pumpEventQueue();
    channel.reply(channel.requested.single, null);
    await loading;

    expect(channel.calls.take(3), <String>['order', 'priority', 'request']);
    expect(channel.decodeOrders, <int>[1]);
    expect(channel.priorityAssets, <List<String>>[
      <String>['a'],
    ]);
  });

  test('after the first load, changes are sent as they happen', () async {
    hints.ensureSynced();
    await pumpEventQueue();
    channel.calls.clear();

    NativeImageProvider.androidDecodeOrder = AndroidDecodeOrder.smallestThenNewest;
    final AndroidDecodePriority grid = AndroidDecodePriority()..update(<String>['a']);
    await pumpEventQueue();

    expect(channel.calls, <String>['order', 'priority']);
    grid.dispose();
    await pumpEventQueue();
    expect(channel.priorityAssets.last, isEmpty);
  });

  test('a hot restart sends the restarted state with the next load', () async {
    NativeImageProvider.androidDecodeOrder = AndroidDecodeOrder.smallestThenNewest;
    hints.ensureSynced();
    await pumpEventQueue();

    AndroidDecodePriority.debugReset();
    hints.ensureSynced();
    await pumpEventQueue();

    expect(channel.decodeOrders, <int>[1, 0]);
    expect(channel.priorityAssets.last, isEmpty);
  });

  test('a send the platform cannot take is retried with the next load', () async {
    channel.failHints = true;
    NativeImageProvider.androidDecodeOrder = AndroidDecodeOrder.smallestThenNewest;
    hints.ensureSynced();
    await pumpEventQueue();
    expect(channel.decodeOrders, isEmpty);

    channel.failHints = false;
    hints.ensureSynced();
    await pumpEventQueue();
    expect(channel.decodeOrders, <int>[1]);
  });

  test('two live screens count together and unchanged updates send nothing', () async {
    hints.ensureSynced();
    final AndroidDecodePriority grid = AndroidDecodePriority()..update(<String>['a', 'b']);
    final AndroidDecodePriority picker = AndroidDecodePriority()..update(<String>['c']);
    await pumpEventQueue();
    expect(channel.priorityAssets.last.toSet(), <String>{'a', 'b', 'c'});

    final int sent = channel.priorityAssets.length;
    grid.update(<String>['b', 'a']);
    await pumpEventQueue();
    expect(channel.priorityAssets.length, sent);

    picker
      ..dispose()
      ..dispose();
    await pumpEventQueue();
    expect(channel.priorityAssets.last.toSet(), <String>{'a', 'b'});
  });

  test('assetIds cannot be changed from outside', () {
    final AndroidDecodePriority grid = AndroidDecodePriority()..update(<String>['a']);
    expect(() => grid.assetIds.add('b'), throwsUnsupportedError);
  });
}
