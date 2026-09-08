import 'package:test/test.dart';

import 'package:mockk_http/src/mockk_http_core.dart';

late MockkHttpCore _core;

void main() {
  setUp(() {
    _core = MockkHttpCore();
    MockkHttpCore.enableDeduplication = true;
    MockkHttpCore.resetDeduplicationState();
  });

  group('capture identity (the claim that replaced the 500 ms window)', () {
    test(
        'a request nobody claimed is the inner layer\'s to capture, however many times',
        () {
      // Two screens asking for the same forecast 12 ms apart, or an immediate retry: the old
      // window dropped the second one inside the app with no trace anywhere. No nonce, no claim.
      expect(_core.consumeInnerPass('not-a-claim'), isFalse);
      expect(_core.consumeInnerPass('not-a-claim'), isFalse);
    });

    test('a claim is consumed by its own nonce exactly once', () {
      final claim =
          _core.claimInnerPass('GET', Uri.parse('http://127.0.0.1/claim'))!;
      expect(MockkHttpCore.debugPendingClaims(), 1);

      expect(_core.consumeInnerPass(claim.nonce), isTrue,
          reason: 'the dio layer above owns this pass');
      expect(claim.consumed, isTrue);
      expect(_core.consumeInnerPass(claim.nonce), isFalse,
          reason: 'a nonce is good for one pass');
      expect(MockkHttpCore.debugPendingClaims(), 0);
    });

    test('a claim can never be charged to another request, whatever its URL',
        () {
      // The identity is the nonce, not the URL: two genuine outer requests hold two different
      // claims, and a request from another stack holds none. Nothing keyed on method, host,
      // path, port or query is left to collide.
      final first =
          _core.claimInnerPass('GET', Uri.parse('http://127.0.0.1/claim'))!;
      final second =
          _core.claimInnerPass('GET', Uri.parse('http://127.0.0.1/claim'))!;
      expect(first.nonce, isNot(second.nonce));
      expect(_core.consumeInnerPass(second.nonce), isTrue);
      expect(_core.consumeInnerPass(second.nonce), isFalse);
      expect(_core.consumeInnerPass(first.nonce), isTrue);
    });

    test(
        'a claim the inner layer never took is withdrawn when the outer request ends',
        () {
      final claim =
          _core.claimInnerPass('GET', Uri.parse('http://127.0.0.1/claim'))!;
      _core.releaseInnerPass(claim);
      expect(MockkHttpCore.debugPendingClaims(), 0);
      expect(_core.consumeInnerPass(claim.nonce), isFalse);
    });

    test('releasing a consumed claim is a no-op', () {
      final claim =
          _core.claimInnerPass('GET', Uri.parse('http://127.0.0.1/claim'))!;
      expect(_core.consumeInnerPass(claim.nonce), isTrue);
      _core.releaseInnerPass(claim);
      expect(MockkHttpCore.debugPendingClaims(), 0);
    });

    test('with coordination off nothing is ever claimed', () {
      MockkHttpCore.enableDeduplication = false;
      expect(_core.claimInnerPass('GET', Uri.parse('http://127.0.0.1/claim')),
          isNull);
      expect(MockkHttpCore.debugPendingClaims(), 0);
    });
  });

  group('flow id entropy', () {
    test('ids generated in a tight loop are unique', () {
      // Both "random" blocks used to be derived from the clock, so every id minted in the same
      // millisecond was identical — routine whenever an app refreshes two endpoints at once.
      final ids = <String>{};
      for (var i = 0; i < 2000; i++) {
        ids.add(MockkHttpCore.debugGenerateId());
      }

      expect(ids.length, 2000,
          reason: 'a duplicated flowId breaks flows/get and from_flow_id');
    });

    test('the two random blocks are not copies of each other', () {
      var identicalPairs = 0;
      for (var i = 0; i < 200; i++) {
        final parts = MockkHttpCore.debugGenerateId().split('-');
        expect(parts.length, 3);
        if (parts[1] == parts[2]) identicalPairs++;
      }

      // Independent 4-hex draws collide about 1 time in 65536; the old clock-derived pair was
      // identical every single time.
      expect(identicalPairs, lessThan(5),
          reason:
              'the two blocks must be independent draws, not one value used twice');
    });

    test('the random blocks actually vary between ids', () {
      final tails = <String>{};
      for (var i = 0; i < 300; i++) {
        tails.add(
            MockkHttpCore.debugGenerateId().split('-').sublist(1).join('-'));
      }

      // The old implementation took the four MOST significant hex digits of a microsecond epoch,
      // which stay constant for hours: this set would have had a single element.
      expect(tails.length, greaterThan(200),
          reason: 'the suffix must carry real entropy');
    });
  });
}
