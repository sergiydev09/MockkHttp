import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:test/test.dart';

import 'package:mockk_http/src/dio_interceptor.dart';
import 'package:mockk_http/src/mockk_http_client.dart';
import 'package:mockk_http/src/mockk_http_core.dart';
import 'package:mockk_http/src/models.dart';

/// A stand-in for the plugin that answers CHECK_MOCK with a fixed reply and
/// RECORDS every message it is sent.
///
/// The recording is the point: with no capture session the plugin used to answer
/// "RECORDING", so the app dutifully buffered every response body and opened a
/// SECOND socket to ship a flow that the plugin then dropped. What must be visible
/// here is that an IDLE answer produces exactly one message — the check itself.

import 'support/fake_plugin.dart';

void main() {
  setUp(() {
    MockkHttpCore.isEnabled = true;
    MockkHttpCore.enableDeduplication = true;
    MockkHttpCore.resetDeduplicationState();
  });

  group('mode fallback', () {
    test('an answer with no mode at all means IDLE, not RECORDING', () {
      // "I do not know" used to be read as "record everything" — the expensive
      // reading of the same uncertainty.
      expect(MockkHttpCore.modeOf(null), MockkHttpCore.modeIdle);
      expect(
        MockkHttpCore.modeOf(const MockCheckResponse(hasMock: false)),
        MockkHttpCore.modeIdle,
      );
    });

    test('a named mode is passed through untouched', () {
      expect(
        MockkHttpCore.modeOf(
            const MockCheckResponse(hasMock: false, mode: 'DEBUG')),
        MockkHttpCore.modeDebug,
      );
      expect(
        MockkHttpCore.modeOf(
            const MockCheckResponse(hasMock: true, mode: 'MOCKK')),
        MockkHttpCore.modeMockk,
      );
    });
  });

  group('HttpOverrides with an idle plugin', () {
    test('sends no flow and returns the real response object', () async {
      final plugin = await FakePlugin.start();
      addTearDown(plugin.close);

      final origin = await startOrigin((request) {
        request.response
          ..statusCode = 200
          ..reasonPhrase = 'Totally Fine'
          ..headers.contentType = ContentType.json
          ..contentLength = 11
          ..write('{"ok":true}');
        request.response.close();
      });
      addTearDown(() => origin.close(force: true));

      final client = clientFor(plugin);
      addTearDown(() => client.close(force: true));

      final request = await client
          .getUrl(Uri.parse('http://127.0.0.1:${origin.port}/weather'));
      final response = await request.close();
      final body = await response.transform(utf8.decoder).join();

      expect(body, '{"ok":true}');
      expect(response.statusCode, 200);

      // Two fingerprints of the ORIGINAL response object. The buffered wrapper
      // synthesises its reason phrase from a small table ('OK' for 200) and its
      // headers report contentLength -1, so neither could survive a capture path.
      expect(response.reasonPhrase, 'Totally Fine',
          reason:
              'an idle plugin must not get a re-wrapped, buffered response');
      expect(response.headers.contentLength, 11);

      await settle();
      expect(plugin.messages, ['CHECK_MOCK'],
          reason:
              'the check is the ONLY thing an idle plugin may cost the app');
      expect(plugin.flowCount, 0);
    });

    test('an answer without a mode field is treated the same way', () async {
      // What the server's failsafe writes when it cannot serve a connection: `{}`.
      final plugin = await FakePlugin.start(mockCheckReply: const {});
      addTearDown(plugin.close);

      final origin = await startOrigin((request) {
        request.response
          ..statusCode = 200
          ..write('hello');
        request.response.close();
      });
      addTearDown(() => origin.close(force: true));

      final client = clientFor(plugin);
      addTearDown(() => client.close(force: true));

      final request = await client
          .getUrl(Uri.parse('http://127.0.0.1:${origin.port}/hello'));
      final response = await request.close();
      expect(await response.transform(utf8.decoder).join(), 'hello');

      await settle();
      expect(plugin.flowCount, 0);
    });

    test('the response body is not buffered', () async {
      final plugin = await FakePlugin.start();
      addTearDown(plugin.close);

      // The origin holds the second half of the body back until we let it go.
      final release = Completer<void>();
      addTearDown(() {
        if (!release.isCompleted) release.complete();
      });

      final origin = await startOrigin((request) async {
        final response = request.response;
        response.add(utf8.encode('first-'));
        await response.flush();
        await release.future;
        response.add(utf8.encode('second'));
        await response.close();
      });
      addTearDown(() => origin.close(force: true));

      final client = clientFor(plugin);
      addTearDown(() => client.close(force: true));

      final request = await client
          .getUrl(Uri.parse('http://127.0.0.1:${origin.port}/stream'));

      // Buffering means awaiting `response.toList()`, which cannot complete until
      // the origin finishes the body — and the origin is waiting on `release`.
      // So a capture path deadlocks here; a pass-through returns on the headers.
      final response = await request.close().timeout(
            const Duration(seconds: 5),
            onTimeout: () =>
                fail('close() waited for the whole body: it was buffered'),
          );

      release.complete();
      expect(await response.transform(utf8.decoder).join(), 'first-second');

      await settle();
      expect(plugin.flowCount, 0);
    });

    test('RECORDING still captures — the harness can see a flow', () async {
      // Control: without this, "no flow arrived" above could just mean the fake
      // plugin never sees anything at all.
      final plugin = await FakePlugin.start(
        mockCheckReply: const {'hasMock': false, 'mode': 'RECORDING'},
      );
      addTearDown(plugin.close);

      final origin = await startOrigin((request) {
        request.response
          ..statusCode = 200
          ..write('{"ok":true}');
        request.response.close();
      });
      addTearDown(() => origin.close(force: true));

      final client = clientFor(plugin);
      addTearDown(() => client.close(force: true));

      final request = await client
          .getUrl(Uri.parse('http://127.0.0.1:${origin.port}/weather'));
      final response = await request.close();
      expect(await response.transform(utf8.decoder).join(), '{"ok":true}');

      await awaitFlow(plugin);
      expect(plugin.checkCount, 1);
      expect(plugin.flowCount, 1,
          reason: 'a running session must still receive the flow');
    });
  });

  group('dio with an idle plugin', () {
    test('sends no flow and leaves no capture state on the request', () async {
      final plugin = await FakePlugin.start();
      addTearDown(plugin.close);

      final origin = await startOrigin((request) {
        request.response
          ..statusCode = 200
          ..headers.contentType = ContentType.json
          ..write('{"ok":true}');
        request.response.close();
      });
      addTearDown(() => origin.close(force: true));

      final dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:${origin.port}'))
        ..interceptors.add(
          MockkHttpDioInterceptor(
            client: MockkHttpPluginClient(port: plugin.port),
            packageName: 'com.test.app',
          ),
        );
      addTearDown(dio.close);

      final response = await dio.post<Map<String, dynamic>>(
        '/forecast',
        data: {'city': 'Sevilla'},
      );

      expect(response.data?['ok'], isTrue);
      expect(response.requestOptions.extra.containsKey('_mockk_request_data'),
          isFalse,
          reason:
              'nothing was captured, so nothing should have been serialised');

      await settle();
      expect(plugin.messages, ['CHECK_MOCK']);
      expect(plugin.flowCount, 0);
    });

    test('RECORDING still captures — the harness can see a flow', () async {
      final plugin = await FakePlugin.start(
        mockCheckReply: const {'hasMock': false, 'mode': 'RECORDING'},
      );
      addTearDown(plugin.close);

      final origin = await startOrigin((request) {
        request.response
          ..statusCode = 200
          ..headers.contentType = ContentType.json
          ..write('{"ok":true}');
        request.response.close();
      });
      addTearDown(() => origin.close(force: true));

      final dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:${origin.port}'))
        ..interceptors.add(
          MockkHttpDioInterceptor(
            client: MockkHttpPluginClient(port: plugin.port),
            packageName: 'com.test.app',
          ),
        );
      addTearDown(dio.close);

      final response = await dio.post<Map<String, dynamic>>(
        '/forecast',
        data: {'city': 'Sevilla'},
      );
      expect(response.data?['ok'], isTrue);

      await awaitFlow(plugin);
      expect(plugin.flowCount, 1);
    });
  });
}
