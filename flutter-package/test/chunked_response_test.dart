import 'dart:convert';
import 'dart:io';

import 'package:test/test.dart';

import 'package:mockk_http/src/models.dart';
import 'package:mockk_http/src/mockk_http_client.dart';

/// A stand-in for the plugin that replies with [reply], deliberately split
/// across several TCP writes with a pause between them.
///
/// This is what the real plugin looks like from the app's side whenever the
/// response does not fit in one segment: `PrintWriter.println` on a large mocked
/// body is delivered as several read events.
Future<ServerSocket> _chunkedServer(String reply, {int chunks = 8}) async {
  final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
  server.listen((socket) async {
    // Wait for the request line before answering, like the plugin does.
    await socket.first;

    final payload = '$reply\n';
    final size = (payload.length / chunks).ceil();
    for (var i = 0; i < payload.length; i += size) {
      socket.add(utf8.encode(payload.substring(
        i,
        i + size > payload.length ? payload.length : i + size,
      )));
      await socket.flush();
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    await socket.close();
  });
  return server;
}

FlowData _flow() => const FlowData(
      flowId: 'flow-1',
      request: RequestData(
        method: 'GET',
        url: 'https://api.example.com/users',
        headers: {},
      ),
      response: ResponseData(statusCode: 200, headers: {}),
      timestamp: 0,
      duration: 0,
    );

void main() {
  group('chunked replies from the plugin', () {
    test('a modified response larger than one TCP read is not truncated',
        () async {
      // ~64 KB of body: comfortably more than a single read event.
      final body = jsonEncode({
        'users': List.generate(
          800,
          (i) => {'id': i, 'name': 'user-$i', 'email': 'user-$i@example.com'},
        ),
      });
      final reply = jsonEncode({
        'statusCode': 201,
        'headers': {'Content-Type': 'application/json'},
        'body': body,
      });

      final server = await _chunkedServer(reply);
      addTearDown(server.close);

      final client = MockkHttpPluginClient(port: server.port);
      final modified = await client.sendFlowAndWait(_flow());

      expect(modified, isNotNull,
          reason: 'a split reply must still parse as one message');
      expect(modified!.statusCode, 201);
      expect(modified.body, body,
          reason: 'the body must be reassembled byte for byte');
      expect(modified.headers?['Content-Type'], 'application/json');
    });

    test('a mock check split across reads is reassembled', () async {
      final reply = jsonEncode({
        'hasMock': true,
        'mode': 'MOCKK',
        'statusCode': 500,
        'headers': <String, String>{},
        'body': 'x' * 40000,
        'mockRuleName': 'server error',
      });

      final server = await _chunkedServer(reply);
      addTearDown(server.close);

      final client = MockkHttpPluginClient(port: server.port);
      final check = await client.checkForMock(
        const RequestData(method: 'GET', url: 'https://api.example.com/x', headers: {}),
      );

      expect(check, isNotNull);
      expect(check!.hasMock, isTrue);
      expect(check.body?.length, 40000);
      expect(check.mockRuleName, 'server error');
    });

    test('multi-byte characters split mid-rune survive reassembly', () async {
      // The decoder must hold partial UTF-8 sequences across chunk boundaries.
      final body = '¡año! ☕ 日本語 — ' * 2000;
      final reply = jsonEncode({
        'statusCode': 200,
        'headers': <String, String>{},
        'body': body,
      });

      final server = await _chunkedServer(reply, chunks: 40);
      addTearDown(server.close);

      final client = MockkHttpPluginClient(port: server.port);
      final modified = await client.sendFlowAndWait(_flow());

      expect(modified?.body, body);
    });
  });
}
