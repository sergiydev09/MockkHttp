import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:mockk_http/mockk_http.dart';
import 'package:test/test.dart';

import 'support/fake_plugin.dart';

/// Audit round four, finding W: the app sent five requests and the plugin recorded four, with
/// no warning anywhere. The 500 ms deduplication window dropped a genuine second request to
/// the same URL inside the app. These tests are the log's promise, asserted on the wire: what
/// the app sent is what the plugin receives — and one request seen by two layers is still one.
void main() {
  setUp(() {
    MockkHttpCore.isEnabled = true;
    MockkHttpCore.enableDeduplication = true;
    MockkHttpCore.resetDeduplicationState();
    MockkHttpCore.resetStats();
  });

  tearDown(() {
    HttpOverrides.global = null;
  });

  Future<HttpServer> okOrigin() => startOrigin((request) {
        request.response
          ..statusCode = 200
          ..headers.contentType = ContentType.json
          ..write('{"ok":true}');
        request.response.close();
      });

  /// Runs [body] with `print` captured: the package's debug warnings are prints inside asserts,
  /// and whether one is emitted — and when — is part of what these tests check.
  Future<List<String>> capturePrints(Future<void> Function() body) async {
    final lines = <String>[];
    await runZoned(
      body,
      zoneSpecification: ZoneSpecification(
          print: (self, parent, zone, line) => lines.add(line)),
    );
    return lines;
  }

  Future<void> settleFlows(FakePlugin plugin, int expected) async {
    final deadline = DateTime.now().add(const Duration(seconds: 3));
    while (plugin.flowCount < expected && DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(const Duration(milliseconds: 20));
    }
    // Give a THIRD, unwanted flow time to arrive before the count is trusted.
    await settle();
  }

  group('HttpOverrides', () {
    test('two identical requests 12 ms apart are two flows', () async {
      final plugin = await FakePlugin.start(
        mockCheckReply: const {'hasMock': false, 'mode': 'RECORDING'},
      );
      addTearDown(plugin.close);
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      final client = clientFor(plugin);
      final url = Uri.parse(
          'http://127.0.0.1:${server.port}/data/2.5/forecast?q=Madrid');

      final first = client.getUrl(url).then((r) => r.close());
      await Future<void>.delayed(const Duration(milliseconds: 12));
      final second = client.getUrl(url).then((r) => r.close());
      await Future.wait([first, second]);

      await settleFlows(plugin, 2);
      expect(plugin.flowCount, 2,
          reason:
              'the app made two requests; a log that keeps one is not a source of truth');
    });
  });

  group('HttpOverrides installed twice', () {
    test('a second install replaces the first instead of wrapping it',
        () async {
      final plugin = await FakePlugin.start(
        mockCheckReply: const {'hasMock': false, 'mode': 'RECORDING'},
      );
      addTearDown(plugin.close);
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));

      // MockkHttp.init() called from two places, or a test installing on top of an app that
      // already did. Two of our wrappers around one client used to capture every request twice.
      for (var i = 0; i < 2; i++) {
        MockkHttpOverrides.install(MockkHttpCore(
          client: MockkHttpPluginClient(port: plugin.port),
          packageName: 'com.test.app',
        ));
      }
      final client = HttpClient();
      addTearDown(client.close);

      await client
          .getUrl(Uri.parse('http://127.0.0.1:${server.port}/data/2.5/weather'))
          .then((r) => r.close());

      await settleFlows(plugin, 1);
      expect(plugin.flowCount, 1, reason: 'one wrapper, one flow');
    });
  });

  group('dio', () {
    Dio dioWith(FakePlugin plugin, HttpServer server,
        {bool attach = true, HttpClientAdapter? adapter}) {
      final dio = Dio(BaseOptions(
          baseUrl: 'http://127.0.0.1:${server.port}',
          validateStatus: (_) => true));
      if (adapter != null) dio.httpClientAdapter = adapter;
      dio.interceptors.add(MockkHttpDioInterceptor(
        client: MockkHttpPluginClient(port: plugin.port),
        packageName: 'com.test.app',
        dio: attach ? dio : null,
      ));
      addTearDown(dio.close);
      return dio;
    }

    void installOverride(FakePlugin plugin) {
      HttpOverrides.global = MockkHttpOverrides(MockkHttpCore(
        client: MockkHttpPluginClient(port: plugin.port),
        packageName: 'com.test.app',
      ));
    }

    Future<FakePlugin> recordingPlugin() async {
      final plugin = await FakePlugin.start(
        mockCheckReply: const {'hasMock': false, 'mode': 'RECORDING'},
      );
      addTearDown(plugin.close);
      return plugin;
    }

    test('two identical requests 12 ms apart are two flows', () async {
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      final dio = dioWith(plugin, server, attach: false);

      final first = dio
          .get<dynamic>('/data/2.5/forecast', queryParameters: {'q': 'Madrid'});
      await Future<void>.delayed(const Duration(milliseconds: 12));
      final second = dio
          .get<dynamic>('/data/2.5/forecast', queryParameters: {'q': 'Madrid'});
      await Future.wait([first, second]);

      await settleFlows(plugin, 2);
      expect(plugin.flowCount, 2);
    });

    test(
        'one request seen by dio AND the global HttpOverrides is still one flow',
        () async {
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      // The layering the old window existed for: dio's adapter builds its HttpClient through
      // HttpOverrides.global, so the same request goes past both interceptors.
      installOverride(plugin);
      final dio = dioWith(plugin, server);

      await dio
          .get<dynamic>('/data/2.5/weather', queryParameters: {'q': 'Madrid'});

      await settleFlows(plugin, 1);
      expect(plugin.flowCount, 1,
          reason:
              'the inner layer must step aside for the pass the outer layer claimed');

      // And a second, genuine request through the same two layers is a second flow.
      await dio
          .get<dynamic>('/data/2.5/weather', queryParameters: {'q': 'Madrid'});
      await settleFlows(plugin, 2);
      expect(plugin.flowCount, 2);
      expect(MockkHttpCore.debugPendingClaims(), 0,
          reason: 'every claim was taken');

      // The numbers the plugin will show under `client`: two requests, two flows, both from
      // dio, both passes yielded by the layer underneath. This is the check the audit asked
      // for — that one request is one flow — made visible instead of tested by hand.
      final client = plugin.lastFlow!['client'] as Map<String, dynamic>;
      expect(client['library'], 'mockk_http');
      expect(client['version'], MockkHttp.version);
      final stats = client['stats'] as Map<String, dynamic>;
      expect(stats['flows_sent'], 2);
      expect(stats['flows_sent_by_dio'], 2);
      expect(stats['flows_sent_by_io'], 0);
      expect(stats['claims_made'], 2);
      expect(stats['passes_yielded'], 2);
      expect(stats['claims_withdrawn'], 0);
      expect(stats['wrapper_replaced'], 0);
      expect(stats['claims_not_carried'], 0);
      expect(plugin.lastCheck!['client'], isNotNull,
          reason: 'the report rides on CHECK_MOCK too');
      // The anchors a reader needs to compare counts across app restarts and clears.
      expect(client['run_id'], isA<String>());
      expect(client['started_at'], isA<int>());
    });

    test(
        'an adapter swapped while a request is in flight is counted, not lost in silence',
        () async {
      // Audit round eight, AG: the wrapper is put back at the start of every request; between
      // that and the adapter's fetch the app can still replace the adapter, and then the layer
      // underneath cannot be told to step aside. That request is reported twice — and the
      // report says so, by the fact (the claim never went down) and the cause (the wrapper
      // was found replaced).
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server);
      var swapped = false;
      dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
        if (!swapped) {
          swapped = true;
          dio.httpClientAdapter =
              IOHttpClientAdapter(); // the app, from somewhere else, mid-flight
        }
        handler.next(options);
      }));

      final said = await capturePrints(() async {
        await dio.get<dynamic>('/data/2.5/weather');
        await settleFlows(plugin, 2);
      });
      expect(plugin.flowCount, 2,
          reason: 'the documented residual: nothing below saw the claim');
      final stats = plugin.lastStats!;
      expect(stats['claims_not_carried'], 1,
          reason: 'the fact: the claim never went down');
      expect(stats['wrapper_replaced'], 1,
          reason: 'the cause: the wrapper was found replaced');
      expect(stats['claims_untaken_beneath'], 0);
      expect(stats['claims_not_fetched'], 0);
      expect(stats['passes_yielded'], 0);
      // Audit round ten, AN: the warning is said where the fact exists — when the request that
      // lost its claim ends — and names the counters that carry it.
      expect(
          said.where((line) => line.contains('claims_not_carried')).length, 1,
          reason: 'said once, by the request that went down without its claim');
      // And the very next request is coordinated again: the wrapper was put back.
      await dio.get<dynamic>('/data/2.5/weather');
      await settleFlows(plugin, 3);
      expect(plugin.flowCount, 3);
    });

    test(
        'the swap is still counted when another request overlaps and puts the wrapper back',
        () async {
      // Audit round nine, AG row 2: a request that starts while the slow one is in flight puts
      // the wrapper back and used to erase the only evidence the old detector read. Counting by
      // the claim does not care what the adapter looks like afterwards.
      final plugin = await recordingPlugin();
      // The origin answers /slow late: the slow request goes DOWN early (through whatever
      // adapter is there at that moment) and comes BACK after the overlapping one ran.
      final server = await startOrigin((request) {
        final delay = request.uri.path.endsWith('/slow')
            ? const Duration(milliseconds: 400)
            : Duration.zero;
        Future<void>.delayed(delay).then((_) {
          request.response
            ..statusCode = 200
            ..headers.contentType = ContentType.json
            ..write('{"ok":true}');
          request.response.close();
        });
      });
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server);
      var swapped = false;
      dio.interceptors
          .add(InterceptorsWrapper(onRequest: (options, handler) async {
        if (options.path.endsWith('/slow') && !swapped) {
          swapped = true;
          dio.httpClientAdapter =
              IOHttpClientAdapter(); // the app, mid-flight: the slow fetch goes down raw
        }
        handler.next(options);
      }));

      final slow = dio.get<dynamic>('/data/2.5/slow');
      await Future<void>.delayed(const Duration(milliseconds: 150));
      await dio.get<dynamic>(
          '/data/2.5/weather'); // overlaps: puts the wrapper back, before the slow response
      await slow;

      await settleFlows(plugin, 3);
      expect(plugin.flowCount, 3,
          reason: 'two requests, three flows: the slow one was reported twice');
      final stats = plugin.lastStats!;
      expect(stats['claims_made'], 2);
      expect(stats['passes_yielded'], 1,
          reason: 'the overlapping request was coordinated');
      expect(stats['claims_not_carried'], 1,
          reason: 'the slow one never went down through the wrapper');
      expect(stats['wrapper_replaced'], 1,
          reason: 'noticed by the request that put the wrapper back');
    });

    test(
        'a swap to an adapter that never reaches dart:io is not mistaken for a duplicate',
        () async {
      // Audit round nine, AG row 3: nothing beneath captured, so nothing was reported twice.
      // Since round eleven (AP) the numbers say exactly that: the layer beneath never saw this
      // request, so it is counted apart from a lost claim, and nothing is said.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server);
      var swapped = false;
      dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
        if (!swapped) {
          swapped = true;
          dio.httpClientAdapter = _CannedAdapter();
        }
        handler.next(options);
      }));

      final said = await capturePrints(() async {
        final response = await dio.get<dynamic>('/data/2.5/weather');
        expect(response.statusCode, 299);
        await settleFlows(plugin, 1);
      });
      expect(plugin.flowCount, 1);
      final stats = plugin.lastStats!;
      // Audit round twelve, AT: the adapter WAS replaced while the claim was alive and nothing
      // beneath matched — from here that is indistinguishable from a fetch under a rewritten
      // URL, so it is said as what it is: unresolved, not "nobody saw it".
      expect(stats['claims_unresolved'], 1,
          reason: 'replaced in flight, nothing beneath matched: not knowable');
      expect(stats['claims_not_fetched'], 0);
      expect(stats['claims_not_carried'], 0,
          reason: 'no duplicate is asserted');
      expect(stats['wrapper_replaced'], 1,
          reason: 'the replacement itself did happen');
      expect(stats['flows_sent'], 1,
          reason: 'one request, one flow — and the report agrees');
      expect(
          said.where((line) => line.contains('claims_unresolved')).length, 1);
    });

    test(
        'an adapter that reaches dart:io under a rewritten URL is not mistaken for nobody seeing it',
        () async {
      // Audit round twelve, AT: a signing or discovery adapter swapped in mid-flight fetches
      // through dart:io under a URL of its own. The sighting beneath does not match the claim,
      // the request IS reported twice, and the only honest count is "unresolved", said once.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server);
      var swapped = false;
      dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
        if (!swapped) {
          swapped = true;
          dio.httpClientAdapter = _RewritingAdapter(IOHttpClientAdapter());
        }
        handler.next(options);
      }));

      final said = await capturePrints(() async {
        await dio.get<dynamic>('/data/2.5/weather');
        await settleFlows(plugin, 2);
      });
      expect(plugin.flowCount, 2,
          reason:
              'the duplicate is real: dart:io saw it under the rewritten URL');
      final stats = plugin.lastStats!;
      expect(stats['claims_unresolved'], 1);
      expect(stats['claims_not_fetched'], 0,
          reason: 'never "nobody saw it" when the adapter was replaced');
      expect(stats['claims_not_carried'], 0,
          reason: 'the identity did not match, so no certainty either');
      expect(stats['wrapper_replaced'], 1);
      expect(
          said.where((line) => line.contains('claims_unresolved')).length, 1);
    });

    test(
        'a Dio handed another Dio\'s wrapped adapter still counts its own first swap',
        () async {
      // Adversarial review of round 12: an adapter shared between two Dio instances arrives at
      // the second interceptor already wrapped, so "wrapped once" was never marked there and the
      // first real swap on that Dio went uncounted — a genuine duplicate came out as not_fetched.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dioA = dioWith(plugin, server);
      final dioB = Dio(BaseOptions(
          baseUrl: 'http://127.0.0.1:${server.port}',
          validateStatus: (_) => true));
      addTearDown(dioB.close);
      dioB.httpClientAdapter =
          dioA.httpClientAdapter; // shared, already wrapped by A's interceptor
      dioB.interceptors.add(MockkHttpDioInterceptor(
        client: MockkHttpPluginClient(port: plugin.port),
        packageName: 'com.test.app',
        dio: dioB,
      ));
      var swapped = false;
      dioB.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
        if (!swapped) {
          swapped = true;
          dioB.httpClientAdapter =
              IOHttpClientAdapter(); // the real swap, mid-flight
        }
        handler.next(options);
      }));

      final said = await capturePrints(() async {
        await dioB.get<dynamic>('/data/2.5/weather');
        await settleFlows(plugin, 2);
      });
      expect(plugin.flowCount, 2, reason: 'the duplicate is real');
      final stats = plugin.lastStats!;
      expect(stats['claims_not_carried'], 1,
          reason: 'counted as the lost claim it is');
      expect(stats['claims_not_fetched'], 0);
      expect(stats['claims_unresolved'], 0);
      expect(
          said.where((line) => line.contains('claims_not_carried')).length, 1);
    });

    test('sightings beneath are kept only while a claim could take them', () {
      // Audit round twelve, AU: an app without dio sent thousands of requests through dart:io
      // and the witness list overflowed, publishing "a lost claim could have gone unwitnessed"
      // in a process with no claims at all.
      final url = Uri.parse('http://127.0.0.1/data/2.5/weather');
      for (var i = 0; i < 1100; i++) {
        MockkHttpCore.noteUnclaimedBeneath('GET', url);
      }
      expect(
          MockkHttpCore.clientReport()['stats']['beneath_witness_evicted'], 0,
          reason: 'nothing kept, nothing evicted: no claim was alive');

      final core = MockkHttpCore(
          client: MockkHttpPluginClient(port: 1), packageName: 'com.test.app');
      final claim = core.claimInnerPass('GET', url);
      for (var i = 0; i < 1025; i++) {
        MockkHttpCore.noteUnclaimedBeneath('GET', url);
      }
      expect(
          MockkHttpCore.clientReport()['stats']['beneath_witness_evicted'], 1,
          reason:
              'with a claim alive the list is real, and so is its overflow');
      core.releaseInnerPass(claim, adapterReplacedMeanwhile: true);
      expect(MockkHttpCore.debugPendingClaims(), 0);
    });

    test(
        'a cache above the adapter while the app reconfigures it per request is not a lost claim',
        () async {
      // Audit round eleven, AP (R11-F): the app sets dio.httpClientAdapter on every request from
      // an earlier interceptor (legitimate, R10-6) while a cache answers one request above the
      // adapter. The cached one never went down and nothing beneath saw it; the replacement that
      // used to charge it was the OTHER request's, made legitimately meanwhile.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = Dio(BaseOptions(
          baseUrl: 'http://127.0.0.1:${server.port}',
          validateStatus: (_) => true));
      addTearDown(dio.close);
      dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
        dio.httpClientAdapter = IOHttpClientAdapter();
        handler.next(options);
      }));
      dio.interceptors.add(MockkHttpDioInterceptor(
        client: MockkHttpPluginClient(port: plugin.port),
        packageName: 'com.test.app',
        dio: dio,
      ));
      dio.interceptors
          .add(InterceptorsWrapper(onRequest: (options, handler) async {
        if (options.path.contains('cached')) {
          await Future<void>.delayed(const Duration(milliseconds: 150));
          handler.resolve(
              Response<dynamic>(requestOptions: options, statusCode: 200),
              true);
          return;
        }
        handler.next(options);
      }));

      final said = await capturePrints(() async {
        final cached = dio.get<dynamic>('/cached/weather');
        await Future<void>.delayed(const Duration(milliseconds: 30));
        final live = dio.get<dynamic>('/data/2.5/weather');
        await Future.wait([cached, live]);
        await settleFlows(plugin, 2);
      });
      expect(plugin.flowCount, 2, reason: 'two requests, two flows');
      final stats = plugin.lastStats!;
      expect(stats['claims_made'], 2);
      expect(stats['passes_yielded'], 1,
          reason: 'the live one went down wrapped and was taken');
      // Audit round twelve, AT: this Dio's adapter WAS replaced while the cached claim was alive
      // (by the other request's interceptor), and nothing beneath matched — from inside that is
      // the same picture as a fetch under a rewritten URL, so it is "unresolved", not "nobody
      // saw it". No duplicate is asserted, and the notice says exactly that.
      expect(stats['claims_unresolved'], 1,
          reason: 'replaced in flight, nothing beneath matched');
      expect(stats['claims_not_fetched'], 0);
      expect(stats['claims_not_carried'], 0,
          reason: 'the swap was the other request\'s: no duplicate asserted');
      expect(stats['wrapper_replaced'], 2);
      expect(said.where((line) => line.contains('claims_not_carried')), isEmpty,
          reason: 'no lost claim is asserted');
      expect(
          said.where((line) => line.contains('claims_unresolved')).length, 1);
    });

    test(
        'an unrelated request to the same URL beneath does not turn a cached answer into a lost claim',
        () async {
      // Adversarial review of round eleven: the layer beneath's sighting is matched by identity
      // (method + URL), and an unrelated request — package:http, a bare HttpClient — to the very
      // same URL while a dio claim is alive would have answered for it. A lost claim needs both
      // witnesses: this Dio's adapter replaced while the claim was alive, AND the sighting.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server)
        ..interceptors
            .add(InterceptorsWrapper(onRequest: (options, handler) async {
          await Future<void>.delayed(const Duration(milliseconds: 150));
          handler.resolve(
              Response<dynamic>(requestOptions: options, statusCode: 200),
              true);
        }));
      final bare = clientFor(plugin);
      addTearDown(bare.close);

      final said = await capturePrints(() async {
        final cached = dio.get<dynamic>('/data/2.5/weather');
        await Future<void>.delayed(const Duration(milliseconds: 30));
        await bare
            .getUrl(
                Uri.parse('http://127.0.0.1:${server.port}/data/2.5/weather'))
            .then((r) => r.close())
            .then((r) => r.drain<void>());
        await cached;
        await settleFlows(plugin, 2);
      });
      expect(plugin.flowCount, 2,
          reason: 'two genuine requests, two flows, no duplicate');
      final stats = plugin.lastStats!;
      expect(stats['claims_not_fetched'], 1,
          reason: 'the cached one never reached an adapter');
      expect(stats['claims_not_carried'], 0,
          reason: 'the sighting beneath was somebody else\'s request');
      expect(stats['wrapper_replaced'], 0);
      expect(said.where((line) => line.startsWith('MockkHttp:')), isEmpty);
    });

    test(
        'every report carries a sequence, so one overtaken on the wire is told from the freshest',
        () {
      final first = MockkHttpCore.clientReport()['seq'] as int;
      final second = MockkHttpCore.clientReport()['seq'] as int;
      expect(second, first + 1);
    });

    test(
        'a cache above one Dio is not charged for another Dio configuring its adapter',
        () async {
      // Audit round eleven, AP (R11-G): two Dio instances, each with its own interceptor. dioB
      // sets its adapter after adding the interceptor (AD-1) while dioA answers a request above
      // its own, untouched adapter. Nothing on dioA was replaced, and nothing beneath saw its
      // request — no lost claim, no warning, whatever dioB did meanwhile.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dioA = dioWith(plugin, server)
        ..interceptors
            .add(InterceptorsWrapper(onRequest: (options, handler) async {
          await Future<void>.delayed(const Duration(milliseconds: 150));
          handler.resolve(
              Response<dynamic>(requestOptions: options, statusCode: 200),
              true);
        }));
      final dioB = dioWith(plugin, server);

      final said = await capturePrints(() async {
        final cached = dioA.get<dynamic>('/cached/weather');
        await Future<void>.delayed(const Duration(milliseconds: 30));
        dioB.httpClientAdapter =
            IOHttpClientAdapter(); // AD-1, on the other Dio, mid-flight for A
        final live = dioB.get<dynamic>('/data/2.5/weather');
        await Future.wait([cached, live]);
        await settleFlows(plugin, 2);
      });
      expect(plugin.flowCount, 2);
      final stats = plugin.lastStats!;
      expect(stats['claims_made'], 2);
      expect(stats['passes_yielded'], 1);
      expect(stats['claims_not_fetched'], 1,
          reason: 'A\'s request never reached an adapter');
      expect(stats['claims_not_carried'], 0,
          reason:
              'the replacement was B\'s, and B\'s request went down wrapped');
      expect(stats['wrapper_replaced'], 1);
      expect(said.where((line) => line.startsWith('MockkHttp:')), isEmpty);
    });

    test(
        'evicting a claim is counted, so the size cap can never hide a duplicate',
        () {
      final core = MockkHttpCore(
          client: MockkHttpPluginClient(port: 1), packageName: 'x');
      for (var i = 0; i < 16385; i++) {
        core.claimInnerPass('GET', Uri.parse('http://127.0.0.1/claim'));
      }
      expect(MockkHttpCore.debugPendingClaims(), 16384);
      final stats =
          MockkHttpCore.clientReport()['stats'] as Map<String, dynamic>;
      expect(stats['claims_evicted'], 1);
      MockkHttpCore.resetDeduplicationState();
    });

    test(
        'without the dio instance the interceptor stands back under the global override',
        () async {
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server, attach: false);

      await dio.get<dynamic>('/data/2.5/weather');

      await settleFlows(plugin, 1);
      expect(plugin.flowCount, 1,
          reason:
              'dart:io captures; reporting it here too would be the old duplicate');
      expect(MockkHttpCore.debugPendingClaims(), 0,
          reason: 'nothing to coordinate, nothing claimed');
      final stats = plugin.lastStats!;
      expect(stats['stood_back'], 1);
      expect(stats['flows_sent_by_io'], 1);
      expect(stats['flows_sent_by_dio'], 0);
    });

    test(
        'a request the dio interceptor already owns is not captured twice by a second copy of it',
        () async {
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      final dio = dioWith(plugin, server, attach: false)
        ..interceptors.add(MockkHttpDioInterceptor(
          client: MockkHttpPluginClient(port: plugin.port),
          packageName: 'com.test.app',
        ));

      await dio.get<dynamic>('/data/2.5/weather');

      await settleFlows(plugin, 1);
      expect(plugin.flowCount, 1,
          reason: 'the second interceptor sees the first one\'s marker');
    });

    test(
        'a claim the inner layer never took cannot swallow the next request to the same URL',
        () async {
      // Audit round five, finding X: a dio request answered from a cache interceptor never
      // reaches dart:io, so its claim is never consumed. A genuine request to the very same
      // URL from another stack a moment later must still be captured.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server)
        // A cache: answers after MockkHttp claimed the request, before it reaches dart:io.
        ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
          handler.resolve(Response(
              requestOptions: options,
              statusCode: 200,
              data: '{"cached":true}'));
        }));
      final url = Uri.parse(
          'http://127.0.0.1:${server.port}/data/2.5/weather?q=Madrid');

      await dio.getUri<dynamic>(url);
      await settle();
      final flowsFromDio = plugin.flowCount;

      // The same URL over the wire, from a plain dart:io client, right afterwards.
      final client = clientFor(plugin);
      await client.getUrl(url).then((r) => r.close());

      await settleFlows(plugin, flowsFromDio + 1);
      expect(plugin.flowCount, flowsFromDio + 1,
          reason:
              'the app sent this one over the wire; the journal must hold it');
      // The orphan claim is visible as such: made, never yielded, never withdrawn (no response
      // chain ran) — a number, not a guess.
      final stats = plugin.lastStats!;
      expect(stats['claims_made'], 1);
      expect(stats['passes_yielded'], 0);
    });

    test(
        'the request goes down exactly as the app wrote it, body included, and is one flow',
        () async {
      final plugin = await recordingPlugin();
      final seenByServer = <String>[];
      final server = await startOrigin((request) {
        seenByServer.add(request.uri.toString());
        request.headers.forEach(
            (name, values) => seenByServer.add('$name: ${values.join(",")}'));
        request.response
          ..statusCode = 200
          ..write('{}');
        request.response.close();
      });
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server);

      final response = await dio
          .post<dynamic>('/data/2.5/weather', data: {'city': 'Madrid'});

      await settleFlows(plugin, 1);
      expect(plugin.flowCount, 1,
          reason: 'a request with a body seen by both layers is one flow');
      expect(seenByServer.first, '/data/2.5/weather');
      expect(
          seenByServer.join('\n').toLowerCase(), isNot(contains('mockkhttp')),
          reason: 'nothing of the coordination is on the wire');
      final captured = plugin.lastFlow!['request'] as Map<String, dynamic>;
      expect(captured['url'] as String,
          'http://127.0.0.1:${server.port}/data/2.5/weather');
      expect(response.requestOptions.path, '/data/2.5/weather');
      expect(
          response.requestOptions.extra.containsKey('_mockk_claim'), isFalse);
    });

    test('a URL with a fragment of its own is untouched and still one flow',
        () async {
      // Audit round six, AA: the fragment channel skipped the claim when the app wrote a
      // fragment, and both layers captured. There is no channel on the URL any more.
      final plugin = await recordingPlugin();
      final seenByServer = <String>[];
      final server = await startOrigin((request) {
        seenByServer.add(request.uri.toString());
        request.response
          ..statusCode = 200
          ..write('{}');
        request.response.close();
      });
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server);

      final response =
          await dio.get<dynamic>('/data/2.5/weather?q=Madrid#section');

      await settleFlows(plugin, 1);
      expect(plugin.flowCount, 1,
          reason:
              'one request seen by two layers is one flow, fragment or no fragment');
      expect(seenByServer.single, '/data/2.5/weather?q=Madrid');
      expect(response.requestOptions.uri.fragment, 'section',
          reason: 'the app keeps its own fragment');
    });

    test(
        'later interceptors and the response see the request exactly as the app wrote it',
        () async {
      // Audit round six, AB: a request interceptor after this one, and a response interceptor
      // that resolves without running the chain, both used to see the coordination on the URL.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      String? uriSeenByLaterInterceptor;
      final dio = dioWith(plugin, server)
        ..interceptors.add(InterceptorsWrapper(
          onRequest: (options, handler) {
            uriSeenByLaterInterceptor = options.uri.toString();
            handler.next(options);
          },
          onResponse: (response, handler) => handler.resolve(response),
        ));

      final response = await dio
          .get<dynamic>('/data/2.5/weather', queryParameters: {'q': 'Madrid'});

      await settleFlows(plugin, 1);
      expect(plugin.flowCount, 1);
      final written =
          'http://127.0.0.1:${server.port}/data/2.5/weather?q=Madrid';
      expect(uriSeenByLaterInterceptor, written);
      expect(response.requestOptions.uri.toString(), written);
      expect(response.requestOptions.path, '/data/2.5/weather');
    });

    test(
        'an adapter that bypasses dart:io captures here alone, with nothing claimed',
        () async {
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server, adapter: _CannedAdapter());

      final response = await dio.get<dynamic>('/data/2.5/weather');
      expect(response.statusCode, 299,
          reason: 'served by the canned adapter, not the network');

      await settleFlows(plugin, 1);
      expect(plugin.flowCount, 1,
          reason: 'there is no dart:io layer underneath; this one reports');
      expect(MockkHttpCore.debugPendingClaims(), 0,
          reason: 'the claim was withdrawn when the request ended');
      final stats = plugin.lastStats!;
      expect(stats['claims_untaken_beneath'], 1,
          reason: 'went down through the wrapper; nobody beneath took it');
      expect(stats['claims_not_carried'], 0);
      expect(stats['wrapper_replaced'], 0,
          reason: 'nothing was replaced: no false alarm');
    });

    test(
        'an adapter configured after the interceptor was added is still coordinated',
        () async {
      // Audit round seven, AD-1: pinning, a proxy or timeouts replace dio.httpClientAdapter
      // after the interceptor was added, and that used to throw the wrapper away.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server);
      dio.httpClientAdapter = IOHttpClientAdapter();

      final said = await capturePrints(() async {
        await dio.get<dynamic>('/data/2.5/weather');
        await settleFlows(plugin, 1);
      });
      expect(plugin.flowCount, 1,
          reason: 'the wrapper is put back on every request');
      final stats = plugin.lastStats!;
      expect(stats['passes_yielded'], 1,
          reason: 'coordinated: the claim went down and was taken');
      expect(stats['claims_not_carried'], 0);
      // Audit round ten, AN: configuring the adapter before the traffic is how every app does
      // it, and it lost nothing — so nothing is said. The replacement itself is still counted.
      expect(stats['wrapper_replaced'], 1);
      expect(said.where((line) => line.startsWith('MockkHttp:')), isEmpty,
          reason: 'no request lost its claim: no warning');
    });

    test(
        'an interceptor that sets the adapter on every request is a configuration, not a loss',
        () async {
      // Audit round ten, AN: an earlier interceptor that reconfigures dio.httpClientAdapter per
      // request (a proxy toggle, a per-host client). Each request finds the wrapper gone, puts
      // it back and goes down through it: every claim is taken, nothing is duplicated, and
      // nothing is said. wrapper_replaced counts the replacements, which did happen.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = Dio(BaseOptions(
          baseUrl: 'http://127.0.0.1:${server.port}',
          validateStatus: (_) => true));
      addTearDown(dio.close);
      dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
        dio.httpClientAdapter = IOHttpClientAdapter();
        handler.next(options);
      }));
      dio.interceptors.add(MockkHttpDioInterceptor(
        client: MockkHttpPluginClient(port: plugin.port),
        packageName: 'com.test.app',
        dio: dio,
      ));

      final said = await capturePrints(() async {
        for (var i = 0; i < 4; i++) {
          await dio.get<dynamic>('/data/2.5/weather');
        }
        await settleFlows(plugin, 4);
      });
      expect(plugin.flowCount, 4, reason: 'four requests, four flows');
      final stats = plugin.lastStats!;
      expect(stats['passes_yielded'], 4);
      expect(stats['claims_not_carried'], 0);
      expect(stats['wrapper_replaced'], 4,
          reason: 'a fact of configuration, and true');
      expect(said.where((line) => line.startsWith('MockkHttp:')), isEmpty);
    });

    test(
        'a cache that resolves without propagating leaves a pending claim that is visible and harmless',
        () async {
      // Audit round thirteen, AX: handler.resolve(response) with dio's default flag runs no
      // response interceptor at all, so this layer never sees the request end: it produces no
      // flow (documented in the README: place such a cache before MockkHttp's interceptor, or
      // pass callFollowingResponseInterceptor: true) and its claim stays alive. That claim is
      // published as claims_pending, and it no longer opens the witness list to every other
      // request in the process: sightings are kept by identity only.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server)
        ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
          if (options.path.contains('cached')) {
            handler.resolve(Response<dynamic>(
                requestOptions: options, statusCode: 200)); // the default flag
            return;
          }
          handler.next(options);
        }));

      final cached = await dio.get<dynamic>('/cached/weather');
      expect(cached.statusCode, 200);
      await dio.get<dynamic>('/data/2.5/weather');
      await settleFlows(plugin, 1);
      expect(plugin.flowCount, 1,
          reason: 'the cached one never came back to this layer: documented');
      final stats = plugin.lastStats!;
      expect(stats['claims_made'], 2);
      expect(stats['claims_pending'], 1,
          reason: 'the request that never ended, said as such');
      expect(MockkHttpCore.debugPendingClaims(), 1);

      // With that claim stuck, traffic to OTHER URLs must not fill the witness list...
      for (var i = 0; i < 1100; i++) {
        MockkHttpCore.noteUnclaimedBeneath(
            'GET', Uri.parse('http://127.0.0.1/data/2.5/other'));
      }
      expect(
          MockkHttpCore.clientReport()['stats']['beneath_witness_evicted'], 0,
          reason:
              'sightings are kept by identity: nothing here could answer for the stuck claim');
      // ...while traffic to the claimed URL is bounded like any other.
      for (var i = 0; i < 1030; i++) {
        MockkHttpCore.noteUnclaimedBeneath(
            'GET', Uri.parse('http://127.0.0.1:${server.port}/cached/weather'));
      }
      expect(
          MockkHttpCore.clientReport()['stats']['beneath_witness_evicted'], 6);
    });

    test(
        'a request answered above the adapter is not mistaken for a lost claim',
        () async {
      // Audit round ten, AN: a later interceptor that answers the request itself (a cache) is
      // the other way a claim is never carried — and no adapter was replaced, so it is counted
      // apart from the swap, and nothing is said.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server)
        ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
          handler.resolve(
              Response<dynamic>(requestOptions: options, statusCode: 204),
              true);
        }));

      final said = await capturePrints(() async {
        final response = await dio.get<dynamic>('/data/2.5/weather');
        expect(response.statusCode, 204,
            reason: 'answered by the later interceptor');
        await settleFlows(plugin, 1);
      });
      expect(plugin.flowCount, 1,
          reason: 'this layer captured it; nothing beneath ever saw it');
      final stats = plugin.lastStats!;
      expect(stats['claims_not_fetched'], 1,
          reason: 'never reached the adapter, nothing replaced');
      expect(stats['claims_not_carried'], 0);
      expect(stats['wrapper_replaced'], 0);
      expect(said.where((line) => line.startsWith('MockkHttp:')), isEmpty);
    });

    test(
        'a third-party adapter wrapping dart:io is coordinated without being recognised',
        () async {
      // Audit round seven, AD-3: logging, retry and metrics adapters wrap IOHttpClientAdapter.
      // Nothing here recognises them, and nothing needs to: the request reaches dart:io, the
      // claim is taken there, one flow.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server,
          adapter: _DelegatingAdapter(IOHttpClientAdapter()));

      await dio.get<dynamic>('/data/2.5/weather');

      await settleFlows(plugin, 1);
      expect(plugin.flowCount, 1);
      expect(MockkHttpCore.debugPendingClaims(), 0);
    });

    test(
        'a request held for a while by a later interceptor is still coordinated',
        () async {
      // Audit round seven, AE: a claim is honoured however long its request takes and whatever
      // else is in flight. The fast version of the property; the slow one below is the one that
      // fails against the code AE replaced.
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server)
        ..interceptors
            .add(InterceptorsWrapper(onRequest: (options, handler) async {
          if (options.path.endsWith('/slow')) {
            await Future<void>.delayed(const Duration(milliseconds: 400));
          }
          handler.next(options);
        }));

      final slow = dio.get<dynamic>('/data/2.5/slow');
      for (var i = 0; i < 5; i++) {
        await dio.get<dynamic>('/data/2.5/weather');
      }
      await slow;

      await settleFlows(plugin, 6);
      expect(plugin.flowCount, 6,
          reason: 'six requests, six flows — the slow one counted once');
      expect(MockkHttpCore.debugPendingClaims(), 0);
    });

    test(
      'a request held longer than the sweep that used to exist is still coordinated (slow)',
      () async {
        // Audit round nine, AL: the test that travels with a fix must fail without it. The code
        // AE replaced swept claims older than 30 s — on the NEXT claim, not on a timer — so the
        // reproduction needs a request held past 30 s AND another request claiming after that.
        // Against 8a799e3 this yields three flows for two requests; today, two.
        final plugin = await recordingPlugin();
        final server = await okOrigin();
        addTearDown(() => server.close(force: true));
        installOverride(plugin);
        final dio = dioWith(plugin, server)
          ..interceptors
              .add(InterceptorsWrapper(onRequest: (options, handler) async {
            if (options.path.endsWith('/slow')) {
              await Future<void>.delayed(const Duration(seconds: 31));
            }
            handler.next(options);
          }));

        final slow = dio.get<dynamic>('/data/2.5/slow');
        await Future<void>.delayed(const Duration(milliseconds: 30500));
        await dio.get<dynamic>(
            '/data/2.5/weather'); // the claim that used to trigger the sweep
        await slow;

        await settleFlows(plugin, 2);
        expect(plugin.flowCount, 2,
            reason:
                'two requests, two flows — the slow one must not be counted twice');
        expect((plugin.lastStats!)['passes_yielded'], 2);
      },
      tags: ['slow'],
      timeout: const Timeout(Duration(minutes: 2)),
    );

    test('a mocked MOCKK answer through dio is one flow, not two', () async {
      // resolve(..., true) runs this interceptor\'s own onResponse for the mock it just served;
      // that used to report the request a second time.
      final plugin = await FakePlugin.start(
        mockCheckReply: const {
          'hasMock': true,
          'mode': 'MOCKK',
          'statusCode': 503,
          'body': '{"mock":true}',
          'headers': {'content-type': 'application/json'},
        },
      );
      addTearDown(plugin.close);
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      final dio = dioWith(plugin, server, attach: false);

      final response = await dio.get<dynamic>('/data/2.5/weather');
      expect(response.statusCode, 503, reason: 'the mock answered');

      await settleFlows(plugin, 1);
      expect(plugin.flowCount, 1);
    });

    test(
        'a request the mock answers makes no claim: nothing goes down, nothing is counted',
        () async {
      // Audit round ten, AM: the claim used to be made before the plugin was asked, so every
      // mock served by this layer left a claim nobody beneath could take — counted in the same
      // bucket as a lost one. MOCKK is the mode the tool exists for; its count must be clean.
      final plugin = await FakePlugin.start(
        mockCheckReply: const {
          'hasMock': true,
          'mode': 'MOCKK',
          'statusCode': 503,
          'body': '{"mock":true}',
          'headers': {'content-type': 'application/json'},
        },
      );
      addTearDown(plugin.close);
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server);

      for (var i = 0; i < 3; i++) {
        final response = await dio.get<dynamic>('/data/2.5/weather');
        expect(response.statusCode, 503, reason: 'the mock answered');
      }

      await settleFlows(plugin, 3);
      expect(plugin.flowCount, 3, reason: 'three mocked requests, three flows');
      final stats = plugin.lastStats!;
      expect(stats['claims_made'], 0,
          reason: 'nothing went down, so nothing was claimed');
      expect(stats['claims_not_carried'], 0);
      expect(stats['claims_not_fetched'], 0);
      expect(stats['wrapper_replaced'], 0);
      expect(stats['flows_sent'], 3);
      expect(MockkHttpCore.debugPendingClaims(), 0);
    });

    test(
        'with coordination switched off both layers capture, which is what off means',
        () async {
      MockkHttpCore.enableDeduplication = false;
      final plugin = await recordingPlugin();
      final server = await okOrigin();
      addTearDown(() => server.close(force: true));
      installOverride(plugin);
      final dio = dioWith(plugin, server);

      await dio.get<dynamic>('/data/2.5/weather');

      await settleFlows(plugin, 2);
      expect(plugin.flowCount, 2);
    });
  });
}

/// A dio adapter that reaches dart:io under a URL of its own — a signing or discovery adapter.
class _RewritingAdapter implements HttpClientAdapter {
  _RewritingAdapter(this._inner);

  final HttpClientAdapter _inner;

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) {
    final rewritten = options.copyWith(
        queryParameters: {...options.queryParameters, 'sig': 'abc123'});
    return _inner.fetch(rewritten, requestStream, cancelFuture);
  }

  @override
  void close({bool force = false}) => _inner.close(force: force);
}

/// A dio adapter that never touches dart:io — what a native adapter looks like from here.
class _CannedAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(RequestOptions options,
          Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async =>
      ResponseBody.fromString('{"canned":true}', 299);

  @override
  void close({bool force = false}) {}
}

/// A third-party adapter that wraps the real one — the shape of any logging, retry or metrics
/// adapter for dio.
class _DelegatingAdapter implements HttpClientAdapter {
  _DelegatingAdapter(this._inner);
  final HttpClientAdapter _inner;

  @override
  Future<ResponseBody> fetch(RequestOptions options,
          Stream<Uint8List>? requestStream, Future<void>? cancelFuture) =>
      _inner.fetch(options, requestStream, cancelFuture);

  @override
  void close({bool force = false}) => _inner.close(force: force);
}
